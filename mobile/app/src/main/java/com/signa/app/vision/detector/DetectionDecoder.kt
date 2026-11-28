package com.signa.app.vision.detector

import com.signa.app.domain.model.VisionBox
import com.signa.app.domain.model.VisionDetection
import com.signa.app.domain.model.VisionLabel
import com.signa.app.vision.analyzer.Letterbox
import java.nio.FloatBuffer

internal class DetectionDecoder(
    private val labels: List<VisionLabel>,
    private val confidence: Float,
    private val iou: Float,
    private val maxDet: Int
) {
    init {
        require(labels.isNotEmpty() && labels.indices.all { labels[it].id == it })
        require(confidence in 0f..1f && iou in 0f..1f && maxDet > 0)
    }

    fun decode(values: FloatBuffer, anchors: Int, transform: Letterbox): List<VisionDetection> {
        require(anchors > 0 && values.limit() == (4 + labels.size) * anchors)
        val candidates = ArrayList<Candidate>()
        val size = transform.size
        for (index in 0 until anchors) {
            val x = values[index] * size
            val y = values[anchors + index] * size
            val w = values[2 * anchors + index] * size
            val h = values[3 * anchors + index] * size
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
            var bestClass = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (id in labels.indices) {
                val score = values[(4 + id) * anchors + index]
                if (!score.isFinite()) {
                    bestClass = -1
                    break
                }
                if (score > bestScore) {
                    bestClass = id
                    bestScore = score
                }
            }
            val score = bestScore.coerceIn(0f, 1f)
            if (bestClass == -1 || score < confidence) continue
            val box = VisionBox(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            val original = transform.toOriginal(box)
            if (original.width > 0 && original.height > 0) {
                candidates += Candidate(box, VisionDetection(original, labels[bestClass], score))
            }
        }

        // Sort ổn định theo thứ tự anchor khi bằng điểm. NMS dùng box chưa cắt.
        candidates.sortByDescending { it.detection.confidence }
        val kept = ArrayList<Candidate>()
        for (candidate in candidates) {
            val suppressed = kept.any {
                it.detection.label.id == candidate.detection.label.id && overlaps(it.raw, candidate.raw)
            }
            if (suppressed) continue
            kept += candidate
            if (kept.size == maxDet) break
        }
        return kept.map { it.detection }
    }

    private fun overlaps(first: VisionBox, second: VisionBox): Boolean {
        val intersection = first.intersection(second)
        return intersection > iou * (first.area + second.area - intersection)
    }

    private data class Candidate(val raw: VisionBox, val detection: VisionDetection)
}
