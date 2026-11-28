package com.signa.app.vision.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.signa.app.vision.analyzer.FrameTiming
import com.signa.app.vision.analyzer.VisionFrame
import com.signa.app.vision.analyzer.VisionPipeline
import com.signa.app.vision.detector.TfliteSignDetector
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Benchmark replay runs every frame in order, as fast as the device can process it. */
@RequiresApi(28)
internal suspend fun replayVideo(
    context: Context,
    uri: Uri,
    onFrame: (Bitmap, VisionFrame) -> Unit
): Int = withContext(Dispatchers.Default) {
    val video = MediaMetadataRetriever()
    try {
        video.setDataSource(context, uri)
        val count = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
        require(count != null && count > 0)
        val power = context.getSystemService(PowerManager::class.java)
        val thermalAtStart = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
        val timings = mutableListOf<ReplayTiming>()
        var dimensions = ""
        VisionPipeline(TfliteSignDetector.load(context.assets)).use { pipeline ->
            val started = SystemClock.elapsedRealtimeNanos()
            for (index in 0 until count) {
                ensureActive()
                val decodeStarted = SystemClock.elapsedRealtimeNanos()
                val bitmap = checkNotNull(video.getFrameAtIndex(index))
                val decoded = SystemClock.elapsedRealtimeNanos()
                val frame = pipeline.process(bitmap, started = decoded)
                dimensions = "${bitmap.width}x${bitmap.height}"
                timings += ReplayTiming(decoded - decodeStarted, frame.timing)
                onFrame(bitmap, frame)
            }
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            val summary = replaySummary(timings, elapsed)
                .put("frameSize", dimensions)
                .put("sourceDurationMs", video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
                .put("device", Build.MODEL)
                .put("android", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull())
                .put("runtime", "LiteRT CPU XNNPACK")
                .put("threads", TfliteSignDetector.THREADS)
                .put("thermalStart", thermalAtStart)
                .put("thermalEnd", if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null)
            Log.i("SignaReplay", summary.toString())
        }
        count
    } finally {
        video.release()
    }
}

private data class ReplayTiming(val videoDecodeNs: Long, val vision: FrameTiming)

private fun replaySummary(samples: List<ReplayTiming>, elapsedNs: Long): JSONObject {
    fun stage(nanos: (ReplayTiming) -> Long): JSONObject {
        val sorted = samples.map(nanos).sorted()
        fun percentile(fraction: Double) = sorted[ceil(fraction * sorted.size).toInt() - 1] / 1e6
        return JSONObject().put("p50", percentile(.5)).put("p95", percentile(.95))
    }
    return JSONObject()
        .put("frames", samples.size)
        .put("fps", samples.size * 1e9 / elapsedNs)
        .put("elapsedMs", elapsedNs / 1e6)
        .put("videoDecodeMs", stage { it.videoDecodeNs })
        .put("preprocessMs", stage { it.vision.preprocessNs })
        .put("inferenceMs", stage { it.vision.inferenceNs })
        .put("nmsMs", stage { it.vision.postprocessNs })
        .put("trackerMs", stage { it.vision.trackerNs })
        .put("frameMs", stage { it.videoDecodeNs + it.vision.totalNs })
}
