package org.openpelo.beltstatusdump

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SessionDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at_utc INTEGER NOT NULL,
                ended_at_utc INTEGER,
                active_duration_ms INTEGER NOT NULL DEFAULT 0,
                distance_meters REAL NOT NULL DEFAULT 0,
                max_speed_mps REAL NOT NULL DEFAULT 0,
                speed_unit TEXT NOT NULL,
                interrupted INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE metric_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                elapsed_ms INTEGER NOT NULL,
                speed_mps REAL NOT NULL,
                incline_percent REAL NOT NULL,
                distance_meters REAL NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startSession(speedUnit: SpeedUnit): Long {
        val values = ContentValues().apply {
            put("started_at_utc", System.currentTimeMillis())
            put("speed_unit", speedUnit.name)
        }
        return writableDatabase.insertOrThrow("sessions", null, values)
    }

    fun addSample(
        sessionId: Long,
        elapsedMillis: Long,
        speedMetersPerSecond: Double,
        inclinePercent: Double,
        distanceMeters: Double,
    ) {
        val sample = ContentValues().apply {
            put("session_id", sessionId)
            put("elapsed_ms", elapsedMillis)
            put("speed_mps", speedMetersPerSecond)
            put("incline_percent", inclinePercent)
            put("distance_meters", distanceMeters)
        }
        writableDatabase.insert("metric_samples", null, sample)

        writableDatabase.execSQL(
            """
            UPDATE sessions
            SET active_duration_ms = ?,
                distance_meters = ?,
                max_speed_mps = MAX(max_speed_mps, ?)
            WHERE id = ?
            """.trimIndent(),
            arrayOf(elapsedMillis, distanceMeters, speedMetersPerSecond, sessionId),
        )
    }

    fun finishSession(sessionId: Long, elapsedMillis: Long, distanceMeters: Double) {
        val values = ContentValues().apply {
            put("ended_at_utc", System.currentTimeMillis())
            put("active_duration_ms", elapsedMillis)
            put("distance_meters", distanceMeters)
            put("interrupted", 0)
        }
        writableDatabase.update("sessions", values, "id = ?", arrayOf(sessionId.toString()))
    }

    fun recentSessions(limit: Int = 10): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val lines = mutableListOf<String>()
        readableDatabase.query(
            "sessions",
            arrayOf(
                "id",
                "started_at_utc",
                "active_duration_ms",
                "distance_meters",
                "max_speed_mps",
                "interrupted",
            ),
            null,
            null,
            null,
            null,
            "id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val started = formatter.format(Date(cursor.getLong(1)))
                val duration = formatDuration(cursor.getLong(2))
                val miles = cursor.getDouble(3) / METERS_PER_MILE
                val maxMph = cursor.getDouble(4) / METERS_PER_SECOND_PER_MPH
                val interrupted = cursor.getInt(5) != 0
                lines += String.format(
                    Locale.US,
                    "#%d  %s\n%s  %.2f mi  max %.1f mph%s",
                    id,
                    started,
                    duration,
                    miles,
                    maxMph,
                    if (interrupted) "  (interrupted)" else "",
                )
            }
        }
        return lines.joinToString("\n\n").ifEmpty { "No saved sessions yet." }
    }

    fun exportCsv(): String {
        val output = StringBuilder()
        output.appendLine(
            "session_id,started_at_utc,elapsed_seconds,speed_mph,incline_percent,distance_miles"
        )

        readableDatabase.rawQuery(
            """
            SELECT s.id, s.started_at_utc, m.elapsed_ms, m.speed_mps,
                   m.incline_percent, m.distance_meters
            FROM sessions s
            JOIN metric_samples m ON m.session_id = s.id
            ORDER BY s.id, m.elapsed_ms
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                output.append(
                    String.format(
                        Locale.US,
                        "%d,%d,%.3f,%.3f,%.3f,%.6f\n",
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getLong(2) / 1000.0,
                        cursor.getDouble(3) / METERS_PER_SECOND_PER_MPH,
                        cursor.getDouble(4),
                        cursor.getDouble(5) / METERS_PER_MILE,
                    )
                )
            }
        }
        return output.toString()
    }

    companion object {
        private const val DATABASE_NAME = "tread_metrics.db"
        private const val DATABASE_VERSION = 1
        private const val METERS_PER_MILE = 1609.344
        private const val METERS_PER_SECOND_PER_MPH = 0.44704

        fun formatDuration(milliseconds: Long): String {
            val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
            val hours = totalSeconds / 3600
            val minutes = totalSeconds % 3600 / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
