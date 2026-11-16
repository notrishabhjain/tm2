package com.taskmind.vector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Turns text into a vector of meaning with multilingual-e5-small, on the
 * phone: the model file runs in ONNX Runtime, no network.
 *
 * e5 is trained with a word in front of each text - "passage: " for what is
 * stored, "query: " for what is asked - and its vectors are the average of
 * the last layer over the real tokens, scaled to length one. Two vectors'
 * dot product is then how close the two texts are in meaning, in Hindi,
 * English or a mix.
 *
 * Opening the model takes a second and holds about 150 MB; keep one and
 * close it when done.
 */
class E5Embedder(modelFile: File, private val tokenizer: SpmTokenizer, threads: Int = 4) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.path,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )
    private val wantsTypes = "token_type_ids" in session.inputNames

    enum class Kind(val prefix: String) { QUERY("query: "), PASSAGE("passage: ") }

    fun embed(text: String, kind: Kind, maxTokens: Int = MAX_TOKENS): FloatArray {
        val ids = tokenizer.encode(kind.prefix + text, maxTokens)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { ids[it].toLong() }), shape)
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape)
        val types = if (wantsTypes) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), shape) else null
        try {
            val inputs = buildMap {
                put("input_ids", inputIds)
                put("attention_mask", mask)
                if (types != null) put("token_type_ids", types)
            }
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (result[0].value as Array<Array<FloatArray>>)[0]
                val dim = hidden[0].size
                val out = FloatArray(dim)
                for (t in 0 until n) for (d in 0 until dim) out[d] += hidden[t][d]
                var norm = 0.0
                for (d in 0 until dim) {
                    out[d] /= n
                    norm += out[d] * out[d]
                }
                val scale = (1.0 / sqrt(norm.coerceAtLeast(1e-12))).toFloat()
                for (d in 0 until dim) out[d] *= scale
                return out
            }
        } finally {
            inputIds.close()
            mask.close()
            types?.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Long enough for a stretch of chat; the model's own limit is 512. */
        const val MAX_TOKENS = 256
        const val DIMENSIONS = 384
    }
}
