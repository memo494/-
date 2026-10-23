package com.clu.quickanswer.ocr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Builds the built-in layout templates (assets/templates/builtin.json) from the owner's real
 * screenshots, and checks that each one reads its screens. The screenshots are NOT in this public
 * repository (decision 5): set CLU_REAL_SHOTS to the folder that holds them to run it; otherwise it
 * is skipped. With CLU_WRITE_BUILTIN=1 it also rewrites the asset.
 *
 * Coordinates are pixels of the 2712 x 1220 captures; masks are where an overlay (Clu's panel,
 * bubble or green box, another app's bubble, a toast) covered the game when the shot was taken.
 */
class RealScreenCalibration {

    private class Shot(val file: String, val masks: List<PxRect>)

    private class Pattern(
        val id: String,
        val arrangement: Arrangement,
        val question: PxRect,
        val options: List<PxRect>,
        val field: PxRect?,
        /** The first shot is the one the fingerprint and the signatures are taken from. */
        val shots: List<Shot>,
    )

    private val panel = PxRect(1030, 15, 1945, 152)
    private val bubbleHigh = PxRect(165, 388, 358, 575)
    private val bubbleLow = PxRect(70, 415, 262, 608)
    private val toast = PxRect(860, 790, 1830, 1015)

    private val gridButtons = listOf(
        // Reading order: rows top to bottom, right to left inside a row (Arabic).
        PxRect(1443, 656, 1908, 782), PxRect(944, 656, 1409, 782),
        PxRect(1443, 804, 1908, 930), PxRect(944, 804, 1409, 930),
    )

    private val patterns = listOf(
        Pattern(
            "builtin-parchment-grid", Arrangement.GRID,
            question = PxRect(895, 478, 2045, 598), options = gridButtons, field = null,
            shots = listOf(
                Shot("6e0e9099", emptyList()),
                Shot("d09bfafb", emptyList()),
                Shot("833df123", listOf(PxRect(70, 560, 262, 750))),
                Shot("f6ead0c0", listOf(panel, bubbleHigh, PxRect(1070, 660, 1290, 756))),
                Shot("863254e8", listOf(panel, bubbleHigh, toast)),
                Shot("5ec78b50", listOf(bubbleLow)),
                Shot("e97f5c84", listOf(PxRect(795, 15, 2175, 228), PxRect(58, 362, 248, 553), PxRect(950, 665, 1410, 772))),
            ),
        ),
        Pattern(
            // The tie-break round: no players' bar, everything sits higher.
            "builtin-parchment-grid-tie", Arrangement.GRID,
            question = PxRect(840, 425, 1995, 565),
            options = listOf(
                PxRect(1388, 609, 1854, 735), PxRect(890, 609, 1354, 735),
                PxRect(1388, 757, 1854, 883), PxRect(889, 757, 1354, 883),
            ),
            field = null,
            shots = listOf(Shot("112371d2", emptyList())),
        ),
        Pattern(
            "builtin-parchment-numeric", Arrangement.NUMERIC,
            question = PxRect(780, 445, 1540, 600), options = emptyList(), field = PxRect(950, 645, 1435, 760),
            shots = listOf(
                Shot("3af318bb", emptyList()),
                Shot("182638db", emptyList()),
                Shot("43b15cef", listOf(panel, bubbleHigh, PxRect(910, 612, 1465, 785), toast)),
            ),
        ),
        Pattern(
            "builtin-parchment-numeric-tie", Arrangement.NUMERIC,
            question = PxRect(690, 385, 1505, 550), options = emptyList(), field = PxRect(880, 585, 1385, 715),
            shots = listOf(
                Shot("8845c03e", listOf(panel, bubbleLow)),
                Shot("104bbd5d", listOf(panel, bubbleLow, PxRect(1272, 366, 1617, 644))),
            ),
        ),
        Pattern(
            "builtin-wood-numeric", Arrangement.NUMERIC,
            question = PxRect(640, 60, 1910, 268), options = emptyList(), field = PxRect(665, 280, 1247, 428),
            shots = listOf(
                Shot("32bf9a60", listOf(PxRect(125, 320, 318, 515))),
                Shot("ccb68b52", listOf(PxRect(110, 420, 298, 610))),
            ),
        ),
    )

    private fun load(dir: File, name: String): TestImage {
        val f = File(dir, "$name-image.jpg")
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val image = imageIo.getMethod("read", File::class.java).invoke(null, f) ?: error("cannot decode $f")
        val type = image.javaClass
        val w = type.getMethod("getWidth").invoke(image) as Int
        val h = type.getMethod("getHeight").invoke(image) as Int
        val int = Int::class.javaPrimitiveType
        val getRgb = type.getMethod("getRGB", int, int, int, int, IntArray::class.java, int, int)
        return TestImage(name, getRgb.invoke(image, 0, 0, w, h, null, 0, w) as IntArray, w, h)
    }

    private fun rel(r: PxRect, w: Int, h: Int) =
        RelRect(r.left.toFloat() / w, r.top.toFloat() / h, r.right.toFloat() / w, r.bottom.toFloat() / h)

    @Test fun buildAndCheck() {
        val dir = System.getenv("CLU_REAL_SHOTS")?.let { File(it) }
        assumeTrue("set CLU_REAL_SHOTS to run", dir != null && dir.isDirectory)
        val images = patterns.flatMap { it.shots }.associate { it.file to load(dir!!, it.file) }
        val built = ArrayList<LayoutTemplate>()
        for (p in patterns) {
            val src = images.getValue(p.shots[0].file)
            val w = src.width
            val h = src.height
            val regions = listOf(p.question) + p.options + listOfNotNull(p.field)
            val t = LayoutTemplate(
                id = p.id, packageName = null,
                fingerprint = ScreenFingerprint.of(src.argb, w, h, p.shots[0].masks),
                arrangement = p.arrangement,
                question = rel(p.question, w, h), options = p.options.map { rel(it, w, h) }, numericField = p.field?.let { rel(it, w, h) },
                signatures = regions.map { TemplateReader.signature(src.argb, w, h, it) },
                language = null, state = TemplateState.TRUSTED, hits = 0, misses = 0, source = BuiltinTemplates.SOURCE,
                createdAt = 0L, lastUsedAt = 0L,
            )
            check(t.isValid) { "${p.id} is not valid" }
            built.add(t)
            println("== ${p.id}  fp=${t.fingerprint}")
            println("   sig=${t.signatures}")
        }
        // Every template against every shot: which fit, and what they read.
        for ((name, img) in images) {
            val masks = patterns.flatMap { it.shots }.first { it.file == name }.masks
            val fp = ScreenFingerprint.of(img.argb, img.width, img.height, masks)
            for (t in built) {
                val d = ScreenFingerprint.distance(t.fingerprint, fp)
                val sigs = TemplateReader.signatures(t, img.argb, img.width, img.height)
                val fits = TemplateReader.fits(t, img.argb, img.width, img.height)
                val lr = TemplateReader.layoutForDetailed(t, img.argb, img.width, img.height)
                var read = ""
                if (fits && lr.layout != null) {
                    val q = OcrFixtures.reader.analyze(img.argb, img.width, img.height, masks, ColorLayout.Result(lr.layout, null))
                    val bare = OcrFixtures.reader.analyze(img.argb, img.width, img.height, emptyList(), ColorLayout.Result(lr.layout, null))
                    if (q.question == null) println("      (no masks: ${bare.question?.let { "Q='${it.questionText}' O=${it.options}" } ?: bare.failure})")
                    read = q.question?.let { "Q='${it.questionText}' O=${it.options} num=${it.numeric} conf=${"%.2f".format(it.meanConfidence)}" } ?: "declined: ${q.failure}"
                }
                println("$name ~ ${t.id}: dist=${"%.2f".format(d)} fits=$fits layout=${lr.outcome} sigs=$sigs $read")
            }
        }
        if (System.getenv("CLU_WRITE_BUILTIN") == "1") {
            val out = File("src/main/assets/templates/builtin.json")
            out.parentFile.mkdirs()
            val arr = JSONArray().also { a -> built.forEach { a.put(it.toJson()) } }
            out.writeText(JSONObject().put("version", 1).put("builtin", BuiltinTemplates.VERSION).put("templates", arr).toString(1) + "\n")
            println("wrote ${out.absolutePath}")
        }
    }
}
