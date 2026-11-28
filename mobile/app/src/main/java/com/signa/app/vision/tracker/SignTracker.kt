package com.signa.app.vision.tracker

import com.signa.app.domain.model.VisionDetection
import com.signa.app.domain.model.VisionLabel

internal data class FinishedTrack<Evidence>(
    val label: VisionLabel,
    val confidence: Double,
    val splitVote: Boolean,
    val evidence: Evidence?
)

internal data class TrackUpdate<Evidence>(
    val visible: List<VisionDetection>,
    val finished: List<FinishedTrack<Evidence>>
)

internal class SignTracker<Evidence> {
    private val tracks = mutableListOf<Track<Evidence>>()
    private var frame = 0L

    fun update(
        detections: List<VisionDetection>,
        capture: (VisionDetection) -> Evidence? = { null }
    ): TrackUpdate<Evidence> {
        frame++
        val matches = buildList {
            tracks.forEachIndexed { trackIndex, track ->
                detections.forEachIndexed { detectionIndex, detection ->
                    val iou = track.last.box.iou(detection.box)
                    if (iou >= .3f) add(Match(trackIndex, detectionIndex, iou))
                }
            }
        }.sortedByDescending { it.iou }

        val usedTracks = BooleanArray(tracks.size)
        val usedDetections = BooleanArray(detections.size)
        for ((trackIndex, detectionIndex) in matches) {
            if (usedTracks[trackIndex] || usedDetections[detectionIndex]) continue
            usedTracks[trackIndex] = true
            usedDetections[detectionIndex] = true
            observe(tracks[trackIndex], detections[detectionIndex], capture)
        }
        detections.forEachIndexed { index, detection ->
            if (!usedDetections[index]) {
                val track = Track<Evidence>(frame, detection)
                observe(track, detection, capture)
                tracks += track
            }
        }

        val visible = mutableListOf<VisionDetection>()
        val finished = mutableListOf<FinishedTrack<Evidence>>()
        val iterator = tracks.iterator()
        for (track in iterator) {
            when {
                track.confirmed && frame - track.lastSeen > MAX_MISSES -> {
                    finished += track.finish()
                    iterator.remove()
                }
                !track.confirmed && frame - track.firstSeen >= WINDOW - 1 -> iterator.remove()
                track.confirmed && track.lastSeen == frame -> {
                    val winner = track.winningVote()
                    visible += track.last.copy(label = winner.label, confidence = winner.mean.toFloat())
                }
            }
        }
        return TrackUpdate(visible, finished)
    }

    fun finish(): List<FinishedTrack<Evidence>> {
        val finished = tracks.filter { it.confirmed }.map { it.finish() }
        tracks.clear()
        return finished
    }

    private fun observe(
        track: Track<Evidence>,
        detection: VisionDetection,
        capture: (VisionDetection) -> Evidence?
    ) {
        track.last = detection
        track.lastSeen = frame
        track.hits++
        if (track.hits >= REQUIRED_HITS) track.confirmed = true
        val vote = track.votes.getOrPut(detection.label.id) { Vote(detection.label) }
        val area = detection.box.area
        vote.weight += detection.confidence.toDouble() * area
        vote.confidenceSum += detection.confidence.toDouble()
        vote.count++
        if (area > vote.largestArea) {
            vote.largestArea = area
            vote.evidence = capture(detection)
        }
    }

    private class Track<Evidence>(val firstSeen: Long, var last: VisionDetection) {
        var lastSeen = firstSeen
        var hits = 0
        var confirmed = false
        val votes = linkedMapOf<Int, Vote<Evidence>>()

        fun winningVote(): Vote<Evidence> = checkNotNull(votes.values.maxByOrNull { it.weight })

        fun finish(): FinishedTrack<Evidence> {
            val winner = winningVote()
            return FinishedTrack(winner.label, winner.mean, winner.weight < .6 * votes.values.sumOf { it.weight },
                winner.evidence)
        }
    }

    private class Vote<Evidence>(val label: VisionLabel) {
        var weight = 0.0
        var confidenceSum = 0.0
        var count = 0
        var largestArea = 0f
        var evidence: Evidence? = null
        val mean get() = confidenceSum / count
    }

    private data class Match(val trackIndex: Int, val detectionIndex: Int, val iou: Float)

    private companion object {
        const val WINDOW = 5
        const val REQUIRED_HITS = 3
        const val MAX_MISSES = 10
    }
}
