package com.signa.app.vision.detector

import android.content.res.AssetManager
import com.signa.app.domain.model.VisionDetection
import com.signa.app.domain.model.VisionLabel
import com.signa.app.domain.model.VisionModel
import com.signa.app.vision.analyzer.PreparedFrame
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

internal class ModelHashException : IllegalArgumentException("Mô hình TFLite lỗi kiểm tra SHA-256")

internal class TfliteSignDetector private constructor(
    val model: VisionModel,
    val inputSize: Int,
    private val interpreter: Interpreter,
    private val decoder: DetectionDecoder,
    private val anchors: Int,
    channels: Int
) : AutoCloseable {
    private val output = ByteBuffer.allocateDirect(anchors * channels * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val outputValues = output.asFloatBuffer()

    fun infer(frame: PreparedFrame) {
        interpreter.run(frame.tensor, output.rewind())
    }

    fun decode(frame: PreparedFrame): List<VisionDetection> = decoder.decode(outputValues, anchors, frame.transform)

    override fun close() = interpreter.close()

    companion object {
        const val THREADS = 2
        private const val DIRECTORY = "models"

        fun load(assets: AssetManager): TfliteSignDetector {
            val config = assets.json("model.json")
            val labelMap = assets.json("labels.json")
            val labelsJson = labelMap.getJSONArray("classes")
            val labels = List(labelsJson.length()) { index ->
                val row = labelsJson.getJSONObject(index)
                VisionLabel(row.getInt("id"), row.getString("code"), row.getString("name"))
            }
            val size = config.getInt("imgsz")
            val model = VisionModel(config.getString("name"), config.getString("version"), config.getInt("labelMapVersion"))
            require(labelMap.getInt("version") == model.labelMapVersion && labels.size == 46) {
                "Danh mục nhãn không khớp mô hình"
            }
            val decoder = DetectionDecoder(labels, config.getDouble("threshold").toFloat(),
                config.getDouble("nmsIou").toFloat(), config.getInt("maxDet"))
            val bytes = assets.openFd("$DIRECTORY/${config.getString("file")}").use { file ->
                FileInputStream(file.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, file.startOffset, file.declaredLength)
                }
            }
            verifySha256(bytes, config.getString("sha256"))
            val rt = Interpreter(bytes, Interpreter.Options().setUseXNNPACK(true).setNumThreads(THREADS))
            try {
                val input = rt.getInputTensor(0)
                val output = rt.getOutputTensor(0)
                val shape = output.shape()
                require(input.dataType() == DataType.FLOAT32 && input.shape().contentEquals(intArrayOf(1, size, size, 3))) {
                    "Tensor đầu vào của mô hình không hợp lệ"
                }
                require(output.dataType() == DataType.FLOAT32 && shape.size == 3 &&
                    shape[0] == 1 && shape[1] == labels.size + 4 && shape[2] > 0) {
                    "Tensor đầu ra của mô hình không hợp lệ"
                }
                return TfliteSignDetector(model, size, rt, decoder, shape[2], shape[1])
            } catch (failure: Exception) {
                rt.close()
                throw failure
            }
        }

        internal fun verifySha256(bytes: ByteBuffer, expected: String) {
            val sha = MessageDigest.getInstance("SHA-256").apply { update(bytes.asReadOnlyBuffer()) }
                .digest().joinToString("") { "%02x".format(it) }
            if (sha != expected) throw ModelHashException()
        }

        private fun AssetManager.json(name: String) =
            open("$DIRECTORY/$name").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }
}
