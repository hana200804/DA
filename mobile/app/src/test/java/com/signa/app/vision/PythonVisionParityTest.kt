package com.signa.app.vision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.signa.app.domain.model.VisionBox
import com.signa.app.domain.model.VisionLabel
import com.signa.app.vision.analyzer.FramePreprocessor
import com.signa.app.vision.analyzer.Letterbox
import com.signa.app.vision.detector.DetectionDecoder
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonVisionParityTest {
    private val fixture = open("fixture.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    private val size = fixture["size"].asInt
    private val cases = fixture["cases"].asJsonArray.map { it.asJsonObject }

    @Test
    fun letterboxMatchesPythonTensorByteForByte() {
        val preprocessor = FramePreprocessor(size)
        for (case in cases) {
            val name = case["image"].asString
            val image = open(name).use { ImageIO.read(it) }
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val prepared = preprocessor.prepare(pixels, image.width, image.height)
            val configured = case.transform()
            assertEquals(name, configured, prepared.transform)

            val tensor = prepared.tensor.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            val bytes = ByteBuffer.allocate(tensor.remaining() * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(tensor)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(name, case["inputSha256"].asString, hash)
        }
    }

    @Test
    fun decodeAndNmsMatchPythonOnRawTfliteOutput() {
        val labels = fixture["labels"].asJsonArray.map {
            val label = it.asJsonObject
            VisionLabel(label["id"].asInt, label["code"].asString, label["name"].asString)
        }
        val decoder = DetectionDecoder(labels, fixture["threshold"].asFloat, fixture["nmsIou"].asFloat, fixture["maxDet"].asInt)
        for (case in cases) {
            val name = case["image"].asString
            val raw = open(case["raw"].asString).use { GZIPInputStream(it).readBytes() }
            val values = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val actual = decoder.decode(values, case["anchors"].asInt, case.transform())
            val expected = case["detections"].asJsonArray.map { it.asJsonObject }
            assertEquals(name, expected.size, actual.size)
            var minIou = 1f
            var maxDelta = 0f
            for ((reference, detection) in expected.zip(actual)) {
                val b = reference["box"].asJsonArray.map { it.asFloat }
                val iou = detection.box.iou(VisionBox(b[0], b[1], b[2], b[3]))
                val delta = abs(reference["confidence"].asFloat - detection.confidence)
                assertEquals(name, reference["code"].asString, detection.label.code)
                assertTrue("$name IoU=$iou", iou >= .99f)
                assertTrue("$name chênh lệch confidence=$delta", delta <= 1e-4f)
                minIou = minOf(minIou, iou)
                maxDelta = maxOf(maxDelta, delta)
            }
            println("$name: ${actual.size} phát hiện, IoU nhỏ nhất $minIou, chênh lệch lớn nhất $maxDelta")
        }
    }

    private fun JsonObject.transform(): Letterbox {
        val spec = getAsJsonObject("letterbox")
        return Letterbox(size, spec["width"].asInt, spec["height"].asInt, spec["resized_width"].asInt,
            spec["resized_height"].asInt, spec["left"].asInt, spec["top"].asInt)
    }

    private fun open(name: String): InputStream = checkNotNull(javaClass.getResourceAsStream("/vision/$name"))
}
