package com.clu.quickanswer.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.clu.quickanswer.R
import org.junit.Rule
import org.junit.Test

class SettingsScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ar"), theme = "Theme.Clu")

    @Test
    fun probe() {
        val view = paparazzi.inflate<android.view.View>(R.layout.activity_main)
        paparazzi.snapshot(view)
    }
}
