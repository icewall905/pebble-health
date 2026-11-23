package org.tdgs.ph.workout

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

internal class WorkoutStore private constructor(context: Context) :
    SQLiteOpenHelper(context, File(context.noBackupFilesDir, "workouts.db").absolutePath, null, 4) {

    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE workout(id TEXT PRIMARY KEY, sport TEXT NOT NULL, started INTEGER NOT NULL,
                ended INTEGER NOT NULL, active INTEGER NOT NULL, state INTEGER NOT NULL, rpe INTEGER,
                title TEXT, notes TEXT, manual INTEGER NOT NULL DEFAULT 0, manual_distance REAL, uploaded INTEGER NOT NULL DEFAULT 0)
        """.trimIndent())
        for ((table, columns) in mapOf(
            "fix" to "wall INTEGER, active INTEGER, segment INTEGER, lat REAL, lon REAL, alt REAL, accuracy REAL, meters REAL",
            "pulse" to "active INTEGER, bpm INTEGER, wall INTEGER, segment INTEGER, rr TEXT, energy INTEGER, from_watch INTEGER NOT NULL DEFAULT 0",
            "lap" to "active INTEGER, meters REAL",
            "strength" to "exercise TEXT, reps INTEGER, kg REAL",
        )) {
            db.execSQL("CREATE TABLE $table(row INTEGER PRIMARY KEY, workout TEXT REFERENCES workout(id) ON DELETE CASCADE, $columns)")
            db.execSQL("CREATE INDEX ${table}_workout ON $table(workout, row)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE workout ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE workout ADD COLUMN notes TEXT")
            db.execSQL("ALTER TABLE workout ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workout ADD COLUMN manual_distance REAL")
            db.execSQL("ALTER TABLE workout ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE pulse ADD COLUMN rr TEXT")
            db.execSQL("ALTER TABLE pulse ADD COLUMN energy INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE pulse ADD COLUMN from_watch INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun saveLog(workout: Workout, new: Boolean, lockMetrics: Boolean = false) {
        val values = ContentValues().apply {
            if (!lockMetrics) put("sport", workout.sport.name)
            put("title", workout.title)
            put("notes", workout.notes)
            if (workout.manual && !lockMetrics) {
                put("started", workout.start)
                put("ended", workout.end)
                put("active", workout.active)
                put("manual_distance", workout.manualDistance)
            }
        }
        if (new) {
            require(workout.manual)
            values.put("id", workout.id)
            values.put("manual", 1)
            values.put("state", 1)
            writableDatabase.insertOrThrow("workout", null, values)
        } else {
            check(writableDatabase.update("workout", values, "id=? AND state!=0 AND manual=?",
                arrayOf(workout.id, if (workout.manual) "1" else "0")) == 1)
        }
    }

    fun start(workout: Workout) {
        insert("workout", "id" to workout.id, "sport" to workout.sport.name,
            "started" to workout.start, "ended" to workout.start, "active" to 0L, "state" to 0)
    }

    fun checkpoint(id: String, wall: Long, active: Long, finished: Boolean = false) {
        val values = ContentValues().apply {
            put("ended", wall)
            put("active", active)
            if (finished) put("state", 1)
        }
        writableDatabase.update("workout", values, "id=? AND state=0", arrayOf(id))
    }

    fun recover() {
        writableDatabase.execSQL("UPDATE workout SET state=2 WHERE state=0")
    }

    fun fix(id: String, p: Fix) = insert("fix", "workout" to id, "wall" to p.time, "active" to p.active,
        "segment" to p.segment, "lat" to p.lat, "lon" to p.lon, "alt" to p.altitude,
        "accuracy" to p.accuracy, "meters" to p.meters)

    fun pulse(id: String, p: Pulse) = insert("pulse", "workout" to id, "active" to p.active, "bpm" to p.bpm,
        "wall" to p.time, "segment" to p.segment, "rr" to p.rrTicks.joinToString(" "), "energy" to p.energyKj, "from_watch" to if (p.fromWatch) 1 else 0)
    fun lap(id: String, p: Lap) = insert("lap", "workout" to id, "active" to p.active, "meters" to p.meters)
    fun sets(id: String, set: StrengthSet, count: Int) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            repeat(count) {
                insert("strength", "workout" to id, "exercise" to set.exercise, "reps" to set.reps, "kg" to set.kg)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun effort(id: String, effort: Int?) {
        val values = ContentValues().apply { if (effort == null) putNull("rpe") else put("rpe", effort) }
        writableDatabase.update("workout", values, "id=?", arrayOf(id))
    }

    fun markUploaded(id: String) {
        val values = ContentValues().apply { put("uploaded", 1) }
        writableDatabase.update("workout", values, "id=? AND state!=0", arrayOf(id))
    }

    fun discardRecording(id: String) {
        writableDatabase.delete("workout", "id=? AND state=0", arrayOf(id))
    }

    fun delete(id: String) { writableDatabase.delete("workout", "id=? AND state!=0", arrayOf(id)) }

    fun contains(id: String): Boolean = query("SELECT 1 FROM workout WHERE id=? AND state!=0",
        arrayOf(id)) { getInt(0) }.isNotEmpty()

    data class HistoryEntry(val workout: Workout, val distance: Double?)

    fun history(limit: Int = -1) = query("""
        SELECT id,sport,started,ended,active,state,rpe,title,notes,manual,manual_distance,uploaded,
            COALESCE(manual_distance, (SELECT meters FROM fix WHERE workout=w.id ORDER BY row DESC LIMIT 1))
        FROM workout w WHERE state!=0 ORDER BY started DESC LIMIT ?
    """.trimIndent(), arrayOf(limit.toString())) { HistoryEntry(workout(), doubleOrNull(12)) }

    fun detail(id: String): WorkoutDetail? {
        val args = arrayOf(id)
        val workout = query("SELECT id,sport,started,ended,active,state,rpe,title,notes,manual,manual_distance,uploaded FROM workout WHERE id=? AND state!=0",
            args, { workout() }).singleOrNull() ?: return null
        return WorkoutDetail(
            workout,
            query("SELECT wall,active,segment,lat,lon,alt,accuracy,meters FROM fix WHERE workout=? ORDER BY row", args) {
                Fix(getLong(0), getLong(1), getInt(2), getDouble(3), getDouble(4), doubleOrNull(5), getFloat(6), getDouble(7))
            },
            query("SELECT active,bpm,wall,segment,rr,energy,from_watch FROM pulse WHERE workout=? ORDER BY row", args) {
                Pulse(getLong(0), getInt(1), getLong(2), getInt(3),
                    getString(4)?.split(' ')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
                    if (isNull(5)) null else getInt(5), fromWatch = getInt(6) != 0)
            },
            query("SELECT active,meters FROM lap WHERE workout=? ORDER BY row", args) { Lap(getLong(0), getDouble(1)) },
            query("SELECT exercise,reps,kg FROM strength WHERE workout=? ORDER BY row", args) {
                StrengthSet(getString(0), getInt(1), doubleOrNull(2))
            },
        )
    }

    private fun insert(table: String, vararg entries: Pair<String, Any?>) {
        val values = ContentValues().apply {
            for ((key, value) in entries) when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is Float -> put(key, value)
                null -> putNull(key)
                else -> error("Unsupported database value")
            }
        }
        writableDatabase.insertOrThrow(table, null, values)
    }

    private fun <T> query(sql: String, args: Array<String>, item: Cursor.() -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.item())
        } }

    companion object {
        private fun Cursor.workout() = Workout(getString(0), Sport.valueOf(getString(1)),
            getLong(2), getLong(3), getLong(4), getInt(5) == 2, if (isNull(6)) null else getInt(6), getString(7), getString(8),
            getInt(9) == 1, doubleOrNull(10), getInt(11) == 1)
        private fun Cursor.doubleOrNull(index: Int) = if (isNull(index)) null else getDouble(index)

        private var instance: WorkoutStore? = null
        @Synchronized fun get(context: Context): WorkoutStore =
            instance ?: WorkoutStore(context.applicationContext).also {
                it.setWriteAheadLoggingEnabled(true)
                it.recover()
                instance = it
            }
    }
}
