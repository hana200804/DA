package com.signa.app.vision

import com.signa.app.domain.model.VisionBox
import com.signa.app.domain.model.VisionDetection
import com.signa.app.domain.model.VisionLabel
import com.signa.app.vision.tracker.SignTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignTrackerTest {
    private val first = VisionLabel(0, "P.130", "Cấm dừng xe và đỗ xe")
    private val second = VisionLabel(1, "P.131a", "Cấm đỗ xe")

    @Test
    fun confirmsOnThirdHitWithinFiveFrames() {
        val tracker = SignTracker<Unit>()
        assertTrue(tracker.update(listOf(detection())).visible.isEmpty())
        assertTrue(tracker.update(emptyList()).visible.isEmpty())
        assertTrue(tracker.update(listOf(detection())).visible.isEmpty())
        assertTrue(tracker.update(emptyList()).visible.isEmpty())
        assertEquals(first, tracker.update(listOf(detection())).visible.single().label)
        assertEquals(1, tracker.finish().size)
    }

    @Test
    fun tentativeTrackWithTwoHitsExpiresWithoutProposal() {
        val tracker = SignTracker<Unit>()
        repeat(2) { tracker.update(listOf(detection())) }
        repeat(3) { assertTrue(tracker.update(emptyList()).finished.isEmpty()) }
        assertTrue(tracker.update(listOf(detection())).visible.isEmpty())
        assertTrue(tracker.finish().isEmpty())
    }

    @Test
    fun recoversAfterTenMissesAndFinishesOnceOnEleventhMiss() {
        val tracker = SignTracker<Unit>()
        repeat(3) { tracker.update(listOf(detection())) }
        repeat(10) {
            val result = tracker.update(emptyList())
            assertTrue(result.finished.isEmpty())
            assertTrue(result.visible.isEmpty())
        }
        assertEquals(first, tracker.update(listOf(detection())).visible.single().label)
        repeat(10) { assertTrue(tracker.update(emptyList()).finished.isEmpty()) }
        assertEquals(first, tracker.update(emptyList()).finished.single().label)
        assertTrue(tracker.finish().isEmpty())
    }

    @Test
    fun changingLabelRespectsWeightedVoteAndUsesLargestWinnerCrop() {
        val tracker = SignTracker<String>()
        tracker.update(listOf(detection(conf = .9f, size = 10))) { "mã A box nhỏ" }
        tracker.update(listOf(detection(second, .6f, 14))) { "mã B box lớn" }
        val update = tracker.update(listOf(detection(conf = .8f, size = 12))) { "mã A box tốt nhất" }
        assertEquals(first, update.visible.single().label)
        tracker.update(listOf(detection(conf = .7f, size = 11))) { error("Không cắt lại box nhỏ hơn") }
        val finished = tracker.finish().single()
        assertEquals(first, finished.label)
        assertEquals(.8, finished.confidence, 1e-6)
        assertFalse(finished.splitVote)
        assertEquals("mã A box tốt nhất", finished.evidence)
    }

    @Test
    fun flagsWinningLabelBelowSixtyPercent() {
        val tracker = SignTracker<Unit>()
        tracker.update(listOf(detection(conf = .4f)))
        tracker.update(listOf(detection(second, .85f)))
        tracker.update(listOf(detection(conf = .4f)))
        val finished = tracker.finish().single()
        assertEquals(second, finished.label)
        assertTrue(finished.splitVote)
    }

    private fun detection(label: VisionLabel = first, conf: Float = .8f, size: Int = 10) =
        VisionDetection(VisionBox(0f, 0f, size.toFloat(), size.toFloat()), label, conf)
}
