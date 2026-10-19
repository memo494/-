package com.clu.quickanswer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * [CtcModel] backed by onnxruntime. The Java API is the same in onnxruntime-android and in the
 * desktop onnxruntime jar, so one class serves the app and the JVM unit tests. The session is
 * created once and reused for every line; [run] may be called from several threads.
 *
 * @param modelBytes the PP-OCRv5 Arabic recogniser (.onnx).
 * @param threads intra-op threads the runtime may use for one inference.
 */
class OrtCtcModel(modelBytes: ByteArray, threads: Int = 4) : CtcModel, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        // The export carries a few unused initialisers; the runtime would warn about each one.
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    }
    private val session: OrtSession = env.createSession(modelBytes, options)
    private val inputName: String = session.inputNames.first()

    override fun run(input: FloatArray, width: Int): CtcLogits {
        val shape = longArrayOf(1, 3, LineRecognizer.HEIGHT.toLong(), width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val outShape = out.info.shape // [1, steps, classes]
                val buf = out.floatBuffer
                val values = FloatArray(buf.remaining())
                buf.get(values)
                return CtcLogits(outShape[1].toInt(), outShape[2].toInt(), values)
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
