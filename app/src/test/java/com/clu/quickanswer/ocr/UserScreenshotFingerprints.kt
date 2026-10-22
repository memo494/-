package com.clu.quickanswer.ocr

/**
 * Tiny 24x12 luma fingerprints sampled from nine user-provided screenshots on
 * 2026-10-22. The full-resolution originals live in the companion fixture ZIP;
 * these inline samples intentionally carry no personal accounts or readable
 * player names and make a binary-free visual regression reference available in CI.
 *
 * The fingerprints are NOT OCR training data and MUST NEVER determine or tap an
 * answer. They only help sanity-check broad game / gallery visual layouts.
 */
object UserScreenshotFingerprints {
    const val WIDTH = 24
    const val HEIGHT = 12

    data class Ref(val filename: String, val type: String, val luma16: String) {
        fun pixels(): IntArray {
            require(luma16.length == WIDTH * HEIGHT)
            return luma16.map { ch ->
                val lum = ch.digitToInt(16) * 17
                (0xff shl 24) or (lum shl 16) or (lum shl 8) or lum
            }.toIntArray()
        }
    }

    val references: List<Ref> = listOf(
        Ref("1000277246.jpg", "dark_numeric_with_confirm", "23333445565555677645797623346876656767875875543323447c8556856587789b766623447cb99aa9998799bba57612337ccccccccca98ccbc35311127cdddccddd777cccc43201117ddd86879d777cccc42300118ddda788ad878ccdc42200119ddda9999d878dcbc4220011addbbaaabca78accb32100114acaabbbbbbaaaaa8211000114444444455667665111"),
        Ref("1000277245.jpg", "dark_numeric_with_confirm", "23333445565555677645797623346876656767875775543323447c8556856587789b766623447cb99aa9998799bba57612337ccccccccca98ccbc35311127cdddccddd777cccc43201117ddd86669d777cccc42200118ddda888ad878ccdc42200119dddbaaabd878dcbc4220011addbbbbbbc999abcb32100114acaabbbbbbbabaa8211000114444444455667665111"),
        Ref("1000277244.jpg", "dark_grid_2x2", "23333446777555555545797623346878756767665675543323447c8877756676669b766623447cb9aba9997799bba57612337ccdddddddcaaccbc35311127cddcccccbccbcccb32101117ddda999a999adccc32100118ddd977798778dcdc32100119ddd876786677dcab3210011addbbaaabaaaabbcb32200114acaabbbbbbbabaa8221000114444444455667665111"),
        Ref("1000277243.jpg", "dark_grid_2x2_with_image", "23333445566555677645797623346876666767875875543323447c8556656587789b766523447cb99aa9998799bba5751267887addddddcaaccbc35311774439cbccccbbbcccc33201a95549a999a9999dccc32300964449977797777dcdc42200756678867686666dcbb4220044abbbbaaabaaaacbcb42100114acaabbbbbbbabaa8311000114444444455667665111"),
        Ref("1000277242.jpg", "dark_grid_2x2", "23333444444433344445797623446888777665555555543324547cccccccccccccbb743222337cccdddddd889cbba32212237cccbccccccbbccbc41111127cddddddddddddccb31101117ddc66668676adccc31000118ddc77779777bdcdc31000119ddd88889888bdcab3100011addbccccccccc9ccb31100114acaabbbbbbbbaaa8311000114444444455667665111"),
        Ref("1000277019.jpg", "white_numeric_with_green_submit", "44499bcccccccccccccc8586454adeeeeeeededeefbb9596445adeffffeeefffffaa94966879eeeddeffffeddede94654985a9b88abbbba88b5a545544545768bbbaabaa8766555444558968eedddddc978955444447eee8ddddb9998c6c75544446ece9dddcbab88d9e65544347e9e9edddbaa88d9d55554546dbd8cccca8888dcd65554446cbb7777777777bbc6555"),
        Ref("1000276493.jpg", "white_numeric_with_green_submit", "43496acccccccccccccc8586414abefeeeeeeeeeefbb9596445acefeeeeeeeeeefaa9496645aeeeddeffffeddc7d94654966b9c88abbbba88525445549757888bbbaabaa898a555444569a88eedddddcaa5955444447eee8ddddb999978755544446ece9dddcbab88dce65544347e9e9edddbaa88d9d55554546dbd8cccca8888dcd65554446cbb7777777777bbc6555"),
        Ref("1000276181.jpg", "gallery_screenshot_negative", "000011100000000001001200000000000000000000000100000000000000000000000000000000000000000000000000233345555555555566666543556a9bccddddddddcbcaa755445dccaaddddddddbaa9c544222544445554455544545222000000000000000000000000000000012210112110000000001000010000000010000100000000000001000000000000"),
        Ref("1000276172.webp", "white_vertical_options", "45655656666666668a9a99854599bfeedeeeeeeeeeeddc95549bdfeeeeedeeeeefe8aa95549fffffffffffffffedd945447b38b9bbbbbbbb99cba6453445566aeddddddeb65633444459a97aeedddeeec8787444446eeeb9ccccccccbbc9e644445edcbaffffefffcab9e644436eabb9cccccccca9b9d545435eddbaeeeddeeebadde645444555555555555555555555")
    )
}
