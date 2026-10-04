package `as`.kargn.munji2.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import `as`.kargn.munji2.domain.Event
import `as`.kargn.munji2.domain.EventType
import `as`.kargn.munji2.domain.Item
import `as`.kargn.munji2.domain.Origin
import `as`.kargn.munji2.domain.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

// Undo is a soft delete so 다시 넣기 can restore the exact same row (same id, same time).
@Entity(tableName = "events")
data class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val at: Long,
    val amount: Double,
    val discarded: Boolean = false,
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    @ColumnInfo(defaultValue = "'LIVE'") val origin: String = Origin.LIVE.name,
) {
    fun toEvent() = Event(id, EventType.valueOf(type), at, amount, discarded, note, Origin.valueOf(origin))
}

@Dao
interface EventDao {
    @Insert suspend fun insert(row: EventRow): Long
    @Query("SELECT * FROM events WHERE deleted = 0 ORDER BY at DESC") fun observe(): Flow<List<EventRow>>
    @Query("SELECT * FROM events WHERE deleted = 0 ORDER BY at DESC") suspend fun all(): List<EventRow>
    @Query("UPDATE events SET deleted = :deleted WHERE id = :id") suspend fun setDeleted(id: Long, deleted: Boolean)
    @Query("UPDATE events SET at = :at, amount = :amount, note = :note, origin = :origin WHERE id = :id")
    suspend fun update(id: Long, at: Long, amount: Double, note: String, origin: String)
}

@Database(entities = [EventRow::class], version = 2)
abstract class MunjiDb : RoomDatabase() {
    abstract fun dao(): EventDao
}

// The phone already holds real doses in v1, so columns are added in place instead of recreating the table.
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE events ADD COLUMN origin TEXT NOT NULL DEFAULT 'LIVE'")
    }
}

class Store private constructor(context: Context) {
    private val app = context.applicationContext
    private val db = Room.databaseBuilder(app, MunjiDb::class.java, "munji2.db").addMigrations(MIGRATION_1_2).build()
    private val prefs = app.getSharedPreferences("munji2", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings
    private val _theme = MutableStateFlow(prefs.getInt("theme", 0))
    /** 0 = system, 1 = light, 2 = dark. */
    val theme: StateFlow<Int> = _theme

    val events: Flow<List<Event>> = db.dao().observe().map { rows -> rows.map { it.toEvent() } }

    suspend fun all(): List<Event> = db.dao().all().map { it.toEvent() }

    suspend fun add(type: EventType, at: Long, amount: Double, discarded: Boolean = false, note: String = "", origin: Origin = Origin.LIVE): Long =
        db.dao().insert(EventRow(type = type.name, at = at, amount = amount, discarded = discarded, note = note, origin = origin.name))

    suspend fun add(e: Event): Long = add(e.type, e.at, e.amount, e.discarded, e.note, e.origin)

    /** Writes time, amount, note and origin back; also used to restore the prior values on 되돌리기. */
    suspend fun update(e: Event) = db.dao().update(e.id, e.at, e.amount, e.note, e.origin.name)

    suspend fun undo(id: Long) = db.dao().setDeleted(id, true)
    suspend fun restore(id: Long) = db.dao().setDeleted(id, false)

    private fun readSettings() = Settings(
        dayCutMin = prefs.getInt("dayCut", 300),
        sleepStartMin = prefs.getInt("sleepStart", 300),
        sleepEndMin = prefs.getInt("sleepEnd", 780),
    )

    fun saveSettings(s: Settings) {
        prefs.edit().putInt("dayCut", s.dayCutMin).putInt("sleepStart", s.sleepStartMin).putInt("sleepEnd", s.sleepEndMin).apply()
        _settings.value = s
    }

    fun saveTheme(t: Int) {
        prefs.edit().putInt("theme", t).apply()
        _theme.value = t
    }

    fun snoozeUntil(item: Item): Long = prefs.getLong("snooze_${item.name}", 0L)
    fun setSnooze(item: Item, until: Long) = prefs.edit().putLong("snooze_${item.name}", until).apply()

    /** True while the last post for this item was silent (sleep window), so unlock should re-post it loudly. */
    fun isSilent(item: Item) = prefs.getBoolean("silent_${item.name}", false)
    fun setSilent(item: Item, v: Boolean) = prefs.edit().putBoolean("silent_${item.name}", v).apply()

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) { instance ?: Store(context).also { instance = it } }
    }
}
