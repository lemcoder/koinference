package io.github.lemcoder.koinference.llamacpp.gguf

internal object GgufParser {
    private val MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    private val DEFAULT_SKIP_KEYS = setOf(
        "tokenizer.chat_template",
        "tokenizer.ggml.scores",
        "tokenizer.ggml.tokens",
        "tokenizer.ggml.token_type",
    )

    fun parse(
        data: ByteArray,
        skipKeys: Set<String> = DEFAULT_SKIP_KEYS,
        arraySummariseThreshold: Int = 1_000,
    ): GgufMetadata {
        val r = ByteReader(data)
        val version = readMagicAndVersion(r)
        val tensorCount = r.readLittleLong()
        val kvCount = r.readLittleLong()
        val meta = readMetaMap(r, kvCount, skipKeys, arraySummariseThreshold)
        return buildStructured(meta, version, tensorCount, kvCount)
    }

    // ── header ─────────────────────────────────────────────────────────────────

    private fun readMagicAndVersion(r: ByteReader): GgufMetadata.GgufVersion {
        val magic = r.readNBytes(4)
        if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("Not a GGUF file (bad magic)")
        return GgufMetadata.GgufVersion.fromCode(r.readLEUInt32())
    }

    // ── metadata key-value section ─────────────────────────────────────────────

    private fun readMetaMap(
        r: ByteReader,
        kvCnt: Long,
        skipKeys: Set<String>,
        arraySummariseThreshold: Int,
    ): Map<String, MetadataValue> = buildMap {
        repeat(kvCnt.toInt()) {
            val key = r.readString()
            val type = MetadataType.fromCode(r.readNBytes(4).leToInt())
            if (key in skipKeys) skipValue(r, type)
            else this[key] = parseValue(r, type, arraySummariseThreshold)
        }
    }

    // ── value parsing ──────────────────────────────────────────────────────────

    private fun parseValue(r: ByteReader, type: MetadataType, arraySummariseThreshold: Int): MetadataValue =
        when (type) {
            MetadataType.UINT8   -> MetadataValue.UInt8(r.readByte().toUByte())
            MetadataType.INT8    -> MetadataValue.Int8(r.readByte().toByte())
            MetadataType.UINT16  -> MetadataValue.UInt16(r.readNBytes(2).leToShort().toUShort())
            MetadataType.INT16   -> MetadataValue.Int16(r.readNBytes(2).leToShort())
            MetadataType.UINT32  -> MetadataValue.UInt32(r.readNBytes(4).leToInt().toUInt())
            MetadataType.INT32   -> MetadataValue.Int32(r.readNBytes(4).leToInt())
            MetadataType.FLOAT32 -> MetadataValue.Float32(Float.fromBits(r.readNBytes(4).leToInt()))
            MetadataType.BOOL    -> {
                val b = r.readByte()
                if (b != 0 && b != 1) throw IllegalStateException("Invalid boolean value: $b")
                MetadataValue.Bool(b != 0)
            }
            MetadataType.STRING  -> MetadataValue.StringVal(r.readString())
            MetadataType.ARRAY   -> {
                val elemType = MetadataType.fromCode(r.readNBytes(4).leToInt())
                val count = r.readLittleLong().toInt()
                if (arraySummariseThreshold >= 0 && count > arraySummariseThreshold) {
                    repeat(count) { skipValue(r, elemType) }
                    MetadataValue.StringVal("Array($elemType, $count items) /* summarised */")
                } else {
                    MetadataValue.ArrayVal(elemType, List(count) { parseValue(r, elemType, arraySummariseThreshold) })
                }
            }
            MetadataType.UINT64  -> MetadataValue.UInt64(r.readNBytes(8).leToLong().toULong())
            MetadataType.INT64   -> MetadataValue.Int64(r.readNBytes(8).leToLong())
            MetadataType.FLOAT64 -> MetadataValue.Float64(Double.fromBits(r.readNBytes(8).leToLong()))
        }

    private fun skipValue(r: ByteReader, type: MetadataType) {
        when (type) {
            MetadataType.UINT8, MetadataType.INT8, MetadataType.BOOL -> r.skip(1)
            MetadataType.UINT16, MetadataType.INT16                  -> r.skip(2)
            MetadataType.UINT32, MetadataType.INT32, MetadataType.FLOAT32 -> r.skip(4)
            MetadataType.UINT64, MetadataType.INT64, MetadataType.FLOAT64 -> r.skip(8)
            MetadataType.STRING -> r.skip(r.readLittleLong())
            MetadataType.ARRAY  -> {
                val elemType = MetadataType.fromCode(r.readNBytes(4).leToInt())
                val count = r.readLittleLong().toInt()
                repeat(count) { skipValue(r, elemType) }
            }
        }
    }

    // ── structured metadata builder ────────────────────────────────────────────

    private fun buildStructured(
        m: Map<String, MetadataValue>,
        version: GgufMetadata.GgufVersion,
        tensorCnt: Long,
        kvCnt: Long,
    ): GgufMetadata {
        fun String.str()  = (m[this] as? MetadataValue.StringVal)?.value
        fun String.bool() = (m[this] as? MetadataValue.Bool)?.value
        fun String.u32()  = (m[this] as? MetadataValue.UInt32)?.value?.toInt()
            ?: (m[this] as? MetadataValue.Int32)?.value
        fun String.f32()  = (m[this] as? MetadataValue.Float32)?.value
        fun String.strList(): List<String>? =
            (m[this] as? MetadataValue.ArrayVal)
                ?.elements?.mapNotNull { (it as? MetadataValue.StringVal)?.value }

        val arch = "general.architecture".str() ?: "llama"

        val basic = GgufMetadata.BasicInfo(
            uuid      = "general.uuid".str(),
            name      = "general.basename".str(),
            nameLabel = "general.name".str(),
            sizeLabel = "general.size_label".str(),
        )

        val author = GgufMetadata.AuthorInfo(
            organization = "general.organization".str(),
            author       = "general.author".str(),
            doi          = "general.doi".str(),
            url          = "general.url".str(),
            repoUrl      = "general.repo_url".str(),
            license      = "general.license".str(),
            licenseLink  = "general.license.link".str(),
        ).nullIfAllNull { organization == null && author == null && doi == null &&
                url == null && repoUrl == null && license == null && licenseLink == null }

        val additional = GgufMetadata.AdditionalInfo(
            type        = "general.type".str(),
            description = "general.description".str(),
            tags        = "general.tags".strList(),
            languages   = "general.languages".strList(),
        ).nullIfAllNull { type == null && description == null && tags == null && languages == null }

        val architectureInfo = GgufMetadata.ArchitectureInfo(
            architecture        = arch,
            fileType            = "general.file_type".u32(),
            vocabSize           = "$arch.vocab_size".u32(),
            finetune            = "general.finetune".str(),
            quantizationVersion = "general.quantization_version".u32(),
        ).nullIfAllNull { fileType == null && vocabSize == null && finetune == null && quantizationVersion == null }

        val baseModels = buildList {
            val n = "general.base_model.count".u32() ?: 0
            for (i in 0 until n) {
                fun k(s: String) = "general.base_model.$i.$s"
                add(GgufMetadata.BaseModelInfo(
                    name         = k("name").str(),
                    author       = k("author").str(),
                    version      = k("version").str(),
                    organization = k("organization").str(),
                    url          = k("url").str(),
                    doi          = k("doi").str(),
                    uuid         = k("uuid").str(),
                    repoUrl      = k("repo_url").str(),
                ))
            }
        }.takeIf { it.isNotEmpty() }

        val tokenizer = GgufMetadata.TokenizerInfo(
            model          = "tokenizer.ggml.model".str(),
            bosTokenId     = "tokenizer.ggml.bos_token_id".u32(),
            eosTokenId     = "tokenizer.ggml.eos_token_id".u32(),
            unknownTokenId = "tokenizer.ggml.unknown_token_id".u32(),
            paddingTokenId = "tokenizer.ggml.padding_token_id".u32(),
            addBosToken    = "tokenizer.ggml.add_bos_token".bool(),
            addEosToken    = "tokenizer.ggml.add_eos_token".bool(),
            chatTemplate   = "tokenizer.chat_template".str(),
        ).nullIfAllNull { model == null && bosTokenId == null && eosTokenId == null &&
                unknownTokenId == null && paddingTokenId == null &&
                addBosToken == null && addEosToken == null && chatTemplate == null }

        val dimensions = GgufMetadata.DimensionsInfo(
            contextLength   = "$arch.context_length".u32(),
            embeddingSize   = "$arch.embedding_length".u32(),
            blockCount      = "$arch.block_count".u32(),
            feedForwardSize = "$arch.feed_forward_length".u32(),
        ).nullIfAllNull { contextLength == null && embeddingSize == null && blockCount == null && feedForwardSize == null }

        val attention = GgufMetadata.AttentionInfo(
            headCount           = "$arch.attention.head_count".u32(),
            headCountKv         = "$arch.attention.head_count_kv".u32(),
            keyLength           = "$arch.attention.key_length".u32(),
            valueLength         = "$arch.attention.value_length".u32(),
            layerNormEpsilon    = "$arch.attention.layer_norm_epsilon".f32(),
            layerNormRmsEpsilon = "$arch.attention.layer_norm_rms_epsilon".f32(),
        ).nullIfAllNull { headCount == null && headCountKv == null && keyLength == null &&
                valueLength == null && layerNormEpsilon == null && layerNormRmsEpsilon == null }

        val rope = GgufMetadata.RopeInfo(
            frequencyBase         = "$arch.rope.freq_base".f32(),
            dimensionCount        = "$arch.rope.dimension_count".u32(),
            scalingType           = "$arch.rope.scaling.type".str(),
            scalingFactor         = "$arch.rope.scaling.factor".f32(),
            attnFactor            = "$arch.rope.scaling.attn_factor".f32(),
            originalContextLength = "$arch.rope.scaling.original_context_length".u32(),
            finetuned             = "$arch.rope.scaling.finetuned".bool(),
        ).nullIfAllNull { frequencyBase == null && dimensionCount == null && scalingType == null &&
                scalingFactor == null && attnFactor == null && originalContextLength == null && finetuned == null }

        val experts = GgufMetadata.ExpertsInfo(
            count     = "$arch.expert_count".u32(),
            usedCount = "$arch.expert_used_count".u32(),
        ).nullIfAllNull { count == null && usedCount == null }

        return GgufMetadata(
            version = version,
            tensorCount = tensorCnt,
            kvCount = kvCnt,
            basic = basic,
            author = author,
            additional = additional,
            architecture = architectureInfo,
            baseModels = baseModels,
            tokenizer = tokenizer,
            dimensions = dimensions,
            attention = attention,
            rope = rope,
            experts = experts,
        )
    }

    private fun <T> T.nullIfAllNull(predicate: T.() -> Boolean): T? = if (predicate()) null else this

    // ── internal types ─────────────────────────────────────────────────────────

    private enum class MetadataType(val code: Int) {
        UINT8(0), INT8(1), UINT16(2), INT16(3),
        UINT32(4), INT32(5), FLOAT32(6), BOOL(7),
        STRING(8), ARRAY(9), UINT64(10), INT64(11), FLOAT64(12);

        companion object {
            fun fromCode(code: Int): MetadataType =
                entries.firstOrNull { it.code == code }
                    ?: throw IllegalArgumentException("Unknown metadata type code: $code")
        }
    }

    private sealed class MetadataValue {
        data class UInt8(val value: kotlin.UByte) : MetadataValue()
        data class Int8(val value: Byte) : MetadataValue()
        data class UInt16(val value: kotlin.UShort) : MetadataValue()
        data class Int16(val value: Short) : MetadataValue()
        data class UInt32(val value: kotlin.UInt) : MetadataValue()
        data class Int32(val value: Int) : MetadataValue()
        data class Float32(val value: Float) : MetadataValue()
        data class Bool(val value: Boolean) : MetadataValue()
        data class StringVal(val value: String) : MetadataValue()
        data class ArrayVal(val elementType: MetadataType, val elements: List<MetadataValue>) : MetadataValue()
        data class UInt64(val value: kotlin.ULong) : MetadataValue()
        data class Int64(val value: Long) : MetadataValue()
        data class Float64(val value: Double) : MetadataValue()
    }

    // ── byte reader ────────────────────────────────────────────────────────────

    private class ByteReader(private val data: ByteArray) {
        private var pos: Int = 0

        fun readByte(): Int {
            if (pos >= data.size) throw IllegalStateException("Unexpected EOF")
            return data[pos++].toInt() and 0xFF
        }

        fun readNBytes(n: Int): ByteArray {
            if (pos + n > data.size) throw IllegalStateException("Unexpected EOF: need $n, have ${data.size - pos}")
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun skip(n: Long) {
            val skip = n.toInt()
            if (pos + skip > data.size) throw IllegalStateException("Unexpected EOF while skipping")
            pos += skip
        }

        fun readLittleLong(): Long = readNBytes(8).leToLong()

        fun readLEUInt32(): Int = readNBytes(4).leToInt()

        fun readString(): String {
            val len = readLittleLong()
            if (len < 0 || len > Int.MAX_VALUE) throw IllegalStateException("String too long: $len")
            return readNBytes(len.toInt()).decodeToString()
        }
    }

    // ── little-endian helpers ──────────────────────────────────────────────────

    private fun ByteArray.leToShort(): Short =
        ((this[1].toInt() and 0xFF shl 8) or (this[0].toInt() and 0xFF)).toShort()

    private fun ByteArray.leToInt(): Int =
        (this[3].toInt() and 0xFF shl 24) or
        (this[2].toInt() and 0xFF shl 16) or
        (this[1].toInt() and 0xFF shl  8) or
        (this[0].toInt() and 0xFF)

    private fun ByteArray.leToLong(): Long =
        (this[7].toLong() and 0xFF shl 56) or
        (this[6].toLong() and 0xFF shl 48) or
        (this[5].toLong() and 0xFF shl 40) or
        (this[4].toLong() and 0xFF shl 32) or
        (this[3].toLong() and 0xFF shl 24) or
        (this[2].toLong() and 0xFF shl 16) or
        (this[1].toLong() and 0xFF shl  8) or
        (this[0].toLong() and 0xFF)
}
