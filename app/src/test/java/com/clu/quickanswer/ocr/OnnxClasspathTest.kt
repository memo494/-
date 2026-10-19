package com.clu.quickanswer.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * onnxruntime-android and the desktop onnxruntime jar both define ai.onnxruntime.*. The app uses
 * the first, these tests must run on the second (see app/build.gradle.kts). This guards that setup.
 */
class OnnxClasspathTest {

    private val entries: List<String> = System.getProperty("java.class.path").split(File.pathSeparator)

    @Test
    fun theAndroidArtifactIsNotOnTheUnitTestClasspath() {
        val android = entries.filter { it.contains("onnxruntime-android") }
        assertFalse("Android onnxruntime leaked onto the unit-test classpath: $android", android.isNotEmpty())
        // The AAR's classes.jar is extracted to a transforms-* folder; make sure it is not there either.
        val aarClasses = entries.filter { it.contains("onnxruntime") && it.endsWith("classes.jar") }
        assertTrue("onnxruntime classes.jar on classpath: $aarClasses", aarClasses.isEmpty())
    }

    @Test
    fun ortClassesComeFromTheDesktopJar() {
        val location = Class.forName("ai.onnxruntime.OrtSession").protectionDomain.codeSource.location.toString()
        println("[OCR classpath] ai.onnxruntime loaded from $location")
        assertTrue("unexpected onnxruntime location: $location", location.contains("onnxruntime-1.30.0.jar"))
    }
}
