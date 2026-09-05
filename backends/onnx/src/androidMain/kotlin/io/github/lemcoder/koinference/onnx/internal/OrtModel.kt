package io.github.lemcoder.koinference.onnx.internal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/**
 * One loaded graph.
 *
 * This file is duplicated verbatim in the other leg — see [OrtBridge].
 */
internal class OrtModel(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : OnnxModel {

    /**
     * Which inputs this graph declares.
     *
     * Asked rather than assumed: encoders exported from the same model differ here — plenty take no
     * `token_type_ids`, and handing an ONNX session an input it does not declare is an error rather
     * than something it ignores.
     */
    override val inputNames: Set<String> = session.inputNames.toSet()

    override val hiddenSize: Int = session.outputInfo.values.first()
        .let { info -> (info.info as ai.onnxruntime.TensorInfo).shape.last().toInt() }

    override fun run(batch: TokenBatch): Array<Array<FloatArray>> {
        val shape = longArrayOf(batch.size.toLong(), batch.length.toLong())
        val tensors = mutableMapOf<String, OnnxTensor>()

        return try {
            tensors["input_ids"] = tensor(batch.inputIds, shape)
            if ("attention_mask" in inputNames) {
                tensors["attention_mask"] = tensor(batch.attentionMask, shape)
            }
            if ("token_type_ids" in inputNames) {
                tensors["token_type_ids"] = tensor(batch.tokenTypeIds, shape)
            }

            session.run(tensors).use { result ->
                @Suppress("UNCHECKED_CAST")
                // The first output is last_hidden_state for every encoder this backend targets;
                // models that also emit a pooled output put it second.
                (result.get(0).value as Array<Array<FloatArray>>)
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun tensor(rows: Array<LongArray>, shape: LongArray): OnnxTensor {
        val buffer = LongBuffer.allocate(rows.size * (shape[1].toInt()))
        rows.forEach { buffer.put(it) }
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    override fun close() = session.close()
}
