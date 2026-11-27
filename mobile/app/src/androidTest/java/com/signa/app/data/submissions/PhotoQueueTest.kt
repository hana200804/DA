package com.signa.app.data.submissions

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.signa.app.data.local.submissions.PhotoQueueDatabase
import com.signa.app.data.local.submissions.QueuedPhoto
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoQueueTest {
    @Test
    fun loai_ban_ghi_cu_nhat_va_giu_client_id_khi_thu_lai() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PhotoQueueDatabase::class.java).build()
        try {
            val dao = db.queue()
            val ids = List(501) { UUID.randomUUID().toString() }
            val owner = "REMOTE:account"
            ids.forEach { id ->
                dao.enqueue(photo(id, owner))
            }
            val queue = dao.observe(owner).first()
            assertEquals(500, queue.size)
            assertFalse(queue.any { it.clientId == ids.first() })
            assertEquals(ids.last(), queue.first().clientId)

            val firstAttempt = checkNotNull(dao.next(owner))
            dao.enqueue(photo(ids[1], owner).copy(metadataJson = "duplicate"))
            val nextAttempt = checkNotNull(dao.next(owner))
            assertEquals(ids[1], nextAttempt.clientId)
            assertEquals(firstAttempt.orderId, nextAttempt.orderId)
            assertEquals(firstAttempt.metadataJson, nextAttempt.metadataJson)
            assertArrayEquals(firstAttempt.jpeg, nextAttempt.jpeg)
            assertFalse(dao.hasPending("REMOTE:another-account"))
        } finally {
            db.close()
        }
    }

    private fun photo(id: String, owner: String) = QueuedPhoto(
        clientId = id, owner = owner, signTypeCode = "P.123a", capturedAt = "2026-01-15T08:30:12Z",
        metadataJson = """{"clientId":"$id"}""", jpeg = byteArrayOf(1, 2, 3)
    )
}
