package dev.atoll.app.compose

import android.content.Context
import androidx.room.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "submissions", primaryKeys = ["owner", "id"])
data class SubmissionRecord(val owner: String, val id: String, val channelId: String, val createdAt: Long, val json: String)
@Entity(tableName = "drafts", primaryKeys = ["owner", "channelId"])
data class DraftRecord(val owner: String, val channelId: String, val json: String)
@Dao interface OutboxDao {
    @Query("SELECT * FROM submissions WHERE owner=:owner ORDER BY createdAt") suspend fun restore(owner: String): List<SubmissionRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMany(rows: List<SubmissionRecord>)
    @Query("DELETE FROM submissions WHERE owner=:owner AND id=:id") suspend fun remove(owner: String, id: String)
    @Query("SELECT * FROM drafts WHERE owner=:owner") suspend fun drafts(owner: String): List<DraftRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(row: DraftRecord)
}
@Database(entities = [SubmissionRecord::class, DraftRecord::class], version = 1, exportSchema = false)
abstract class ComposerDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context.applicationContext, ComposerDatabase::class.java, "atoll-composer.db").build() }
}
/** ownerKey must include server world + principal, so another account never replays these rows. */
class RoomOutboxRepository(private val ownerKey: String, private val database: ComposerDatabase) : OutboxRepository {
    private val codec = Json { ignoreUnknownKeys = true }
    override suspend fun restore() = database.outbox().restore(ownerKey).map { codec.decodeFromString<LocalSubmission>(it.json) }
    override suspend fun putMany(rows: List<LocalSubmission>) = database.withTransaction {
        database.outbox().putMany(rows.map { SubmissionRecord(ownerKey, it.messageId, it.channelId, it.createdAt, codec.encodeToString(it)) })
    }
    override suspend fun remove(messageId: String) = database.outbox().remove(ownerKey, messageId)
    override suspend fun restoreDrafts() = database.outbox().drafts(ownerKey).associate { it.channelId to codec.decodeFromString<ComposerDraft>(it.json) }
    override suspend fun putDraft(channelId: String, draft: ComposerDraft) = database.outbox().putDraft(DraftRecord(ownerKey, channelId, codec.encodeToString(draft)))
}
