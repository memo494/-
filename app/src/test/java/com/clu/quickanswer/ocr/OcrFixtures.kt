package com.clu.quickanswer.ocr

import java.io.File
import java.io.InputStream

/** A decoded test image: row-major ARGB ints. */
class TestImage(val name: String, val argb: IntArray, val width: Int, val height: Int)

/**
 * Shared test fixtures: the screenshots in src/test/resources/ocr, and one reader around the real
 * model from the app's assets (an onnxruntime session is expensive, so it is built once per JVM).
 *
 * Set the environment variable CLU_OCR_MODEL to the path of another .onnx file (for example the
 * int8 variant) to run the whole suite on it instead of the shipped fp32 model. CLU_OCR_THREADS
 * (threads per inference, default 4) and CLU_OCR_LINES (lines read at once, default 1) time other
 * settings of the runtime on the same screenshots.
 */
object OcrFixtures {

    val modelPath: String = System.getenv("CLU_OCR_MODEL")?.takeIf { it.isNotBlank() }
        ?: assetPath("inference.onnx")

    val modelLabel: String = if (modelPath.contains("int8")) "int8" else "fp32"

    val dictionary: List<String> by lazy {
        LineRecognizer.parseDictionary(File(assetPath("dict.txt")).readText(Charsets.UTF_8))
    }

    private fun envInt(name: String, default: Int): Int = System.getenv(name)?.toIntOrNull()?.takeIf { it > 0 } ?: default

    val threads: Int = envInt("CLU_OCR_THREADS", 4)

    val parallelLines: Int = envInt("CLU_OCR_LINES", 1)

    val model: OrtCtcModel by lazy { OrtCtcModel(File(modelPath).readBytes(), threads = threads) }

    val recognizer: LineRecognizer by lazy { LineRecognizer(model, dictionary) }

    val reader: ScreenReader by lazy {
        ScreenReader(recognizer, lineBatch = if (parallelLines > 1) ParallelLines(parallelLines) else LineBatch.SEQUENTIAL)
    }

    fun image(name: String): TestImage {
        val stream = OcrFixtures::class.java.getResourceAsStream("/ocr/$name.jpg")
            ?: error("missing test resource /ocr/$name.jpg")
        return stream.use { decode(name, it) }
    }

    /**
     * Decodes an image with javax.imageio through reflection: the unit-test sources are compiled
     * against the Android stubs, which have no javax.imageio, but the JVM that runs them does.
     */
    private fun decode(name: String, stream: InputStream): TestImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = imageIo.getMethod("read", InputStream::class.java).invoke(null, stream)
            ?: error("cannot decode $name")
        val type = image.javaClass
        val w = type.getMethod("getWidth").invoke(image) as Int
        val h = type.getMethod("getHeight").invoke(image) as Int
        val int = Int::class.javaPrimitiveType
        val getRgb = type.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
        return TestImage(name, getRgb.invoke(image, 0, 0, w, h, null, 0, w) as IntArray, w, h)
    }

    /** [src] scaled by [factor] with the same box filter the layout uses. */
    fun scaled(src: TestImage, factor: Double): TestImage {
        val w = (src.width * factor).toInt()
        val h = (src.height * factor).toInt()
        return TestImage("${src.name}@$factor", ImageOps.areaResize(src.argb, src.width, src.height, w, h), w, h)
    }

    private fun assetPath(file: String): String {
        // Gradle runs unit tests with the module directory (app/) as the working directory.
        val candidates = listOf("src/main/assets/ocr/$file", "app/src/main/assets/ocr/$file")
        return candidates.firstOrNull { File(it).isFile } ?: error("cannot find assets/ocr/$file from ${File(".").absolutePath}")
    }
}
