package com.signa.app.vision.detector

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.signa.app.domain.model.VisionBox
import com.signa.app.vision.analyzer.FramePreprocessor
import java.nio.ByteBuffer
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TfliteSignDetectorTest {
    @Test
    fun androidInterpreterMatchesPythonReference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testAssets = instrumentation.context.assets
        val reference = testAssets.open("fixture.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val cases = reference.getJSONArray("cases")
        val appAssets = instrumentation.targetContext.assets
        val appConfig = appAssets.open("models/model.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertEquals(reference.getString("modelSha256"), appConfig.getString("sha256"))

        TfliteSignDetector.load(appAssets).use { detector ->
            assertEquals(1, detector.model.labelMapVersion)
            val preprocessor = FramePreprocessor(detector.inputSize)
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val name = case.getString("image")
                val bitmap = testAssets.open(name).use { BitmapFactory.decodeStream(it) }
                val frame = try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    preprocessor.prepare(pixels, bitmap.width, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
                detector.infer(frame)
                val actual = detector.decode(frame)
                val expected = case.getJSONArray("detections")
                assertEquals(name, expected.length(), actual.size)
                var minIou = 1f
                var maxDelta = 0f
                actual.forEachIndexed { index, detection ->
                    val target = expected.getJSONObject(index)
                    val box = target.getJSONArray("box")
                    val expectedBox = VisionBox(box.getDouble(0).toFloat(), box.getDouble(1).toFloat(),
                        box.getDouble(2).toFloat(), box.getDouble(3).toFloat())
                    val iou = expectedBox.iou(detection.box)
                    val delta = abs(target.getDouble("confidence").toFloat() - detection.confidence)
                    assertEquals(name, target.getString("code"), detection.label.code)
                    // Android LiteRT 1.4.2 và Python 1.2.0 có thể dùng phép tích lũy FP khác nhau.
                    assertTrue("$name IoU=$iou", iou >= .98f)
                    assertTrue("$name chênh lệch confidence=$delta", delta <= .005f)
                    minIou = minOf(minIou, iou)
                    maxDelta = maxOf(maxDelta, delta)
                }
                Log.i("SignaVisionParity", "$name: ${actual.size} phát hiện, minIoU=$minIou, maxDelta=$maxDelta")
            }
        }
    }

    @Test
    fun rejectsModelWithWrongSha256() {
        assertThrows(ModelHashException::class.java) {
            TfliteSignDetector.verifySha256(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), "0".repeat(64))
        }
    }
}
