package com.signa.app.data.local.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.signa.app.data.remote.dto.SignCategoryDto
import com.signa.app.data.remote.dto.SignTypesDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SignCatalogSnapshot(
    val categories: List<SignCategoryDto>,
    val types: SignTypesDto,
    val etag: String? = null
)

interface SignCatalogStore {
    val snapshots: Flow<SignCatalogSnapshot>
    suspend fun save(snapshot: SignCatalogSnapshot)
}

@Entity(tableName = "sign_catalog")
data class SignCatalogEntity(@PrimaryKey val source: String, val payload: String)

@Dao
interface SignCatalogDao {
    @Query("SELECT * FROM sign_catalog WHERE source = :source")
    fun observe(source: String): Flow<SignCatalogEntity?>

    @Upsert
    suspend fun save(entity: SignCatalogEntity)
}

@Database(entities = [SignCatalogEntity::class], version = 1)
abstract class SignCatalogDatabase : RoomDatabase() {
    abstract fun catalog(): SignCatalogDao
}

class RoomSignCatalogStore(
    private val dao: SignCatalogDao,
    private val json: Json,
    private val source: String,
    bundled: SignCatalogSnapshot
) : SignCatalogStore {
    override val snapshots = dao.observe(source).map { entity ->
        entity?.let { json.decodeFromString<SignCatalogSnapshot>(it.payload) } ?: bundled
    }

    override suspend fun save(snapshot: SignCatalogSnapshot) =
        dao.save(SignCatalogEntity(source, json.encodeToString(snapshot)))
}
