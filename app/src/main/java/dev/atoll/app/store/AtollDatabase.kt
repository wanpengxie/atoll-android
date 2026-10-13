package dev.atoll.app.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import dev.atoll.app.core.Envelope
import dev.atoll.app.core.Row
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Entity(tableName = "rows", primaryKeys = ["channelId", "seq"])
data class StoredRow(val channelId: String, val seq: Long, val envelopeJson: String)

@Entity(tableName = "coverage", primaryKeys = ["channelId", "low"])
data class StoredCoverage(val channelId: String, val low: Long, val high: Long)

@Entity(tableName = "channel_meta")
data class StoredChannelMeta(
    @PrimaryKey val channelId: String,
    val headSeq: Long,
    val lastActivity: Long,
    val readPosition: Long,
)

@Dao
interface RowDao {
    @Query("SELECT * FROM rows WHERE channelId = :channelId AND seq < :beforeSeq ORDER BY seq DESC LIMIT :limit")
    suspend fun rows(channelId: String, beforeSeq: Long, limit: Int): List<StoredRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRows(rows: List<StoredRow>): List<Long>

    @Query("SELECT * FROM coverage WHERE channelId = :channelId ORDER BY low")
    suspend fun coverage(channelId: String): List<StoredCoverage>

    @Query("DELETE FROM coverage WHERE channelId = :channelId")
    suspend fun deleteCoverage(channelId: String)

    @Insert
    suspend fun insertCoverage(ranges: List<StoredCoverage>)

    @Query("SELECT * FROM channel_meta WHERE channelId = :channelId")
    suspend fun meta(channelId: String): StoredChannelMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: StoredChannelMeta)

    @Query("SELECT seq FROM rows WHERE channelId = :channelId ORDER BY seq DESC LIMIT 1 OFFSET :offset")
    suspend fun retentionFloor(channelId: String, offset: Int): Long?

    @Query("DELETE FROM rows WHERE channelId = :channelId AND seq < :floor")
    suspend fun deleteBefore(channelId: String, floor: Long)
}

@Database(entities = [StoredRow::class, StoredCoverage::class, StoredChannelMeta::class], version = 1, exportSchema = false)
abstract class AtollDatabase : RoomDatabase() {
    abstract fun rowDao(): RowDao

    companion object {
        /** Use a different name for each server + account; the caller owns that namespace. */
        fun open(context: Context, name: String): AtollDatabase =
            Room.databaseBuilder(context.applicationContext, AtollDatabase::class.java, name).build()
    }
}

/** Only DB queries retain rows. ChannelSync never owns a second row cache. */
class RoomRowStore(private val database: AtollDatabase, private val maxRows: Int = 20_000) : RowStore {
    init { require(maxRows > 0) }
    private val dao = database.rowDao()

    override suspend fun channel(channelId: String): CachedChannel = database.withTransaction {
        val value = dao.meta(channelId)
        CachedChannel(
            value?.let { ChannelMeta(it.channelId, it.headSeq, it.lastActivity, it.readPosition) } ?: ChannelMeta(channelId),
            dao.coverage(channelId).map { CoverageRange(it.low, it.high) },
        )
    }

    override suspend fun rows(channelId: String, beforeSeq: Long, limit: Int): List<Row> {
        require(limit > 0)
        return dao.rows(channelId, beforeSeq, limit).asReversed().map {
            Row(it.channelId, it.seq, Envelope(Json.parseToJsonElement(it.envelopeJson).jsonObject))
        }
    }

    override suspend fun commit(channelId: String, rows: List<Row>, coverage: List<CoverageRange>, meta: ChannelMeta?): List<Row> =
        database.withTransaction {
            require(rows.all { it.channelId == channelId && it.seq > 0 })
            require(meta == null || meta.channelId == channelId)
            val unique = rows.distinctBy { it.seq }.sortedBy { it.seq }
            val insertedIds = dao.insertRows(unique.map { StoredRow(channelId, it.seq, it.envelope.json.toString()) })
            val inserted = unique.filterIndexed { index, _ -> insertedIds[index] != -1L }
            var ranges = mergeCoverage(dao.coverage(channelId).map { CoverageRange(it.low, it.high) } +
                coverage + unique.map { CoverageRange(it.seq, it.seq) })
            // Offset maxRows exists only when there is at least one excess row.
            if (dao.retentionFloor(channelId, maxRows) != null) {
                val floor = requireNotNull(dao.retentionFloor(channelId, maxRows - 1))
                dao.deleteBefore(channelId, floor)
                ranges = clippedCoverage(ranges, floor)
            }
            dao.deleteCoverage(channelId)
            dao.insertCoverage(ranges.map { StoredCoverage(channelId, it.low, it.high) })
            val previous = dao.meta(channelId)
            val next = meta ?: previous?.let { ChannelMeta(it.channelId, it.headSeq, it.lastActivity, it.readPosition) } ?: ChannelMeta(channelId)
            dao.putMeta(StoredChannelMeta(channelId,
                maxOf(next.headSeq, unique.lastOrNull()?.seq ?: 0),
                maxOf(next.lastActivity, unique.maxOfOrNull { it.envelope.tsReceived.takeIf { time -> time > 0 } ?: it.envelope.ts } ?: 0),
                next.readPosition))
            inserted
        }

    override suspend fun clear() {
        // Room's clearAllTables is blocking and may not run on the main thread.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { database.clearAllTables() }
    }
}
