plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Renders layouts to PNG on the JVM (no emulator) for the screenshot tests; test-only, see SettingsScreenshotTest.
    id("app.cash.paparazzi") version "1.3.5" apply false
}
