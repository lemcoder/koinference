package io.github.lemcoder.koinference.onnx.internal

/** How a model turns token states into one vector. */
internal enum class PoolingMode {
    /** The `[CLS]` position, which is what BGE and E5 are trained to embed into. */
    CLS,

    /** The average over real tokens, which is what most sentence-transformers models use. */
    MEAN;

    companion object {
        /**
         * Reads `1_Pooling/config.json`, the file sentence-transformers writes beside a model.
         *
         * A flat object of booleans; a regex rather than a JSON dependency, as with ExecuTorch's
         * stats. Absent or unreadable means [MEAN], which is the more common default — and the
         * backend logs nothing about it, so the mode is reported through
         * `Backend`-visible metadata instead of guessed at silently.
         */
        fun from(config: String?): PoolingMode {
            if (config == null) return MEAN
            val cls = Regex(""""pooling_mode_cls_token"\s*:\s*true""").containsMatchIn(config)
            return if (cls) CLS else MEAN
        }
    }
}
