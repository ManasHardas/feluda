package org.neighborhoodwatchdog.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import org.neighborhoodwatchdog.android.domain.model.ConnectivityEvent
import org.neighborhoodwatchdog.android.domain.model.EventType
import org.neighborhoodwatchdog.android.domain.model.WatchdogJson
import org.neighborhoodwatchdog.android.domain.reporting.EventQueueStore
import org.neighborhoodwatchdog.android.domain.reporting.QueuedEvent
import org.neighborhoodwatchdog.android.domain.reporting.SentEventLog
import org.neighborhoodwatchdog.android.domain.reporting.SentRecord

/** An event waiting to be uploaded. [json] is exactly what will be sent for this event. */
@Entity(tableName = "queued_events", indices = [Index("sequence"), Index("occurredAtMs")])
data class QueuedEventEntity(
    @PrimaryKey val eventId: String,
    val sequence: Long,
    val eventType: String,
    val occurredAtMs: Long,
    val enqueuedAtMs: Long,
    val attempts: Int,
    val json: String,
)

/** One of the last 50 events the server gave a final answer for. */
@Entity(tableName = "sent_events", indices = [Index("sentAtMs")])
data class SentEventEntity(
    @PrimaryKey val eventId: String,
    val sequence: Long,
    val eventType: String,
    val occurredAtMs: Long,
    val sentAtMs: Long,
    val result: String,
    val reason: String?,
    val reportedVia: String?,
    val json: String,
)

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQueued(entity: QueuedEventEntity): Long

    @Query("SELECT * FROM queued_events ORDER BY sequence ASC LIMIT :limit")
    suspend fun oldestQueued(limit: Int): List<QueuedEventEntity>

    @Query("SELECT * FROM queued_events ORDER BY sequence DESC LIMIT :limit")
    fun observeQueued(limit: Int): Flow<List<QueuedEventEntity>>

    @Query("SELECT COUNT(*) FROM queued_events")
    fun observeQueuedCount(): Flow<Int>

    @Query("DELETE FROM queued_events WHERE eventId IN (:ids)")
    suspend fun deleteQueued(ids: List<String>)

    @Query("UPDATE queued_events SET attempts = attempts + 1 WHERE eventId IN (:ids)")
    suspend fun incrementAttempts(ids: List<String>)

    @Query("SELECT COUNT(*) FROM queued_events")
    suspend fun countQueued(): Int

    @Query("DELETE FROM queued_events WHERE occurredAtMs < :cutoffMs")
    suspend fun deleteQueuedBefore(cutoffMs: Long): Int

    @Query(
        "DELETE FROM queued_events WHERE eventId IN (SELECT eventId FROM queued_events " +
            "WHERE eventType IN (:types) ORDER BY sequence ASC LIMIT :limit)",
    )
    suspend fun deleteOldestOfTypes(types: List<String>, limit: Int): Int

    @Query("DELETE FROM queued_events WHERE eventId IN (SELECT eventId FROM queued_events ORDER BY sequence ASC LIMIT :limit)")
    suspend fun deleteOldestQueued(limit: Int): Int

    @Query("DELETE FROM queued_events")
    suspend fun clearQueued()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSent(entities: List<SentEventEntity>)

    @Query(
        "DELETE FROM sent_events WHERE eventId NOT IN " +
            "(SELECT eventId FROM sent_events ORDER BY sentAtMs DESC, sequence DESC LIMIT :keep)",
    )
    suspend fun trimSent(keep: Int)

    @Query("SELECT * FROM sent_events ORDER BY sentAtMs DESC, sequence DESC LIMIT :limit")
    fun observeSent(limit: Int): Flow<List<SentEventEntity>>

    @Query("DELETE FROM sent_events")
    suspend fun clearSent()
}

@Database(entities = [QueuedEventEntity::class, SentEventEntity::class], version = 1, exportSchema = false)
abstract class WatchdogDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
}

class RoomEventQueueStore(private val dao: EventDao) : EventQueueStore {
    override suspend fun insert(event: ConnectivityEvent, nowMs: Long): Boolean = dao.insertQueued(
        QueuedEventEntity(
            eventId = event.eventId,
            sequence = event.sequence,
            eventType = event.eventType.name,
            occurredAtMs = event.occurredAt.toEpochMilli(),
            enqueuedAtMs = nowMs,
            attempts = 0,
            json = WatchdogJson.encodeToString(ConnectivityEvent.serializer(), event),
        ),
    ) != -1L

    override suspend fun oldest(limit: Int): List<QueuedEvent> = dao.oldestQueued(limit).map {
        QueuedEvent(WatchdogJson.decodeFromString(ConnectivityEvent.serializer(), it.json), it.attempts, it.enqueuedAtMs)
    }

    override suspend fun remove(eventIds: Collection<String>) {
        eventIds.chunked(CHUNK).forEach { dao.deleteQueued(it) }
    }

    override suspend fun incrementAttempts(eventIds: Collection<String>) {
        eventIds.chunked(CHUNK).forEach { dao.incrementAttempts(it) }
    }

    override suspend fun count(): Int = dao.countQueued()

    override suspend fun deleteOccurredBefore(cutoffMs: Long): Int = dao.deleteQueuedBefore(cutoffMs)

    override suspend fun deleteOldest(types: Set<EventType>?, limit: Int): Int =
        if (types == null) dao.deleteOldestQueued(limit) else dao.deleteOldestOfTypes(types.map { it.name }, limit)

    override suspend fun clear() = dao.clearQueued()

    private companion object {
        const val CHUNK = 500
    }
}

class RoomSentEventLog(private val dao: EventDao) : SentEventLog {
    override suspend fun record(records: List<SentRecord>) {
        if (records.isEmpty()) return
        dao.insertSent(
            records.map {
                SentEventEntity(
                    eventId = it.event.eventId,
                    sequence = it.event.sequence,
                    eventType = it.event.eventType.name,
                    occurredAtMs = it.event.occurredAt.toEpochMilli(),
                    sentAtMs = it.sentAtMs,
                    result = it.result,
                    reason = it.reason,
                    reportedVia = it.reportedVia?.name?.lowercase(),
                    json = WatchdogJson.encodeToString(ConnectivityEvent.serializer(), it.event),
                )
            },
        )
        dao.trimSent(KEEP)
    }

    override suspend fun clear() = dao.clearSent()

    companion object {
        const val KEEP = 50
    }
}
