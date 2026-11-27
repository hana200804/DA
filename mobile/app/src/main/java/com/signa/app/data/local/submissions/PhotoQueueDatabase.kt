package com.signa.app.data.local.submissions

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

internal const val PHOTO_QUEUE_LIMIT = 500

@Entity(tableName = "photo_queue", indices = [Index(value = ["clientId"], unique = true), Index("owner")])
data class QueuedPhoto(
    @PrimaryKey(autoGenerate = true) val orderId: Long = 0,
    val clientId: String,
    val owner: String,
    val signTypeCode: String,
    val capturedAt: String,
    val metadataJson: String,
    val jpeg: ByteArray,
    val state: String = "QUEUED",
    val error: String? = null
)

data class LocalPhotoStatus(
    val clientId: String,
    val signTypeCode: String,
    val capturedAt: String,
    val state: String,
    val error: String?
)

@Dao
abstract class PhotoQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(photo: QueuedPhoto)

    @Query("DELETE FROM photo_queue WHERE orderId IN (SELECT orderId FROM photo_queue ORDER BY orderId DESC LIMIT -1 OFFSET :limit)")
    abstract suspend fun trim(limit: Int)

    @Transaction
    open suspend fun enqueue(photo: QueuedPhoto) {
        insert(photo)
        trim(PHOTO_QUEUE_LIMIT)
    }

    @Query("SELECT * FROM photo_queue WHERE owner = :owner AND state = 'QUEUED' ORDER BY orderId LIMIT 1")
    abstract suspend fun next(owner: String): QueuedPhoto?

    @Query("SELECT EXISTS(SELECT 1 FROM photo_queue WHERE owner = :owner AND state = 'QUEUED')")
    abstract suspend fun hasPending(owner: String): Boolean

    @Query("SELECT clientId, signTypeCode, capturedAt, state, error FROM photo_queue WHERE owner = :owner ORDER BY orderId DESC")
    abstract fun observe(owner: String): Flow<List<LocalPhotoStatus>>

    @Query("DELETE FROM photo_queue WHERE orderId = :orderId")
    abstract suspend fun remove(orderId: Long)

    @Query("UPDATE photo_queue SET state = 'FAILED', error = :message, jpeg = X'', metadataJson = '' WHERE orderId = :orderId")
    abstract suspend fun fail(orderId: Long, message: String)

    @Query("UPDATE photo_queue SET state = 'RECEIVED', jpeg = X'', metadataJson = '' WHERE orderId = :orderId")
    abstract suspend fun acknowledgeFake(orderId: Long)
}

@Database(entities = [QueuedPhoto::class], version = 1, exportSchema = true)
abstract class PhotoQueueDatabase : RoomDatabase() {
    abstract fun queue(): PhotoQueueDao
}
