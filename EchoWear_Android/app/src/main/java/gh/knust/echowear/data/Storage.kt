package gh.knust.echowear.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.sqlcipher.database.SupportFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

// ============================================================================
//  Layer 1 on the phone: the record store is encrypted at rest with SQLCipher,
//  and the passphrase itself is sealed by a key that lives in the Android
//  hardware keystore and never leaves it. Losing the phone exposes nothing.
//
//  Every row carries the device id it came from. With several wearables on
//  one phone, a vitals table without that column is not merely incomplete -
//  it is actively misleading, because a trend line would silently average
//  three different people's heart rates.
// ============================================================================

@Entity(
    tableName = "vitals",
    indices = [Index(value = ["device_id", "recorded_at"])],
)
data class VitalsRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val bpm: Float,
    @ColumnInfo(name = "sdnn_ms") val sdnnMs: Float,
    @ColumnInfo(name = "rmssd_ms") val rmssdMs: Float,
    @ColumnInfo(name = "pnn50_pct") val pnn50Pct: Float,
    @ColumnInfo(name = "motion_tier") val motionTier: Int,
    @ColumnInfo(name = "hrv_weight") val hrvWeight: Float,
    val worn: Boolean,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int,
)

@Entity(
    tableName = "events",
    indices = [Index(value = ["device_id", "occurred_at"])],
)
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "device_id") val deviceId: String,
    /** Pseudonymous patient reference, e.g. P-0003. Never a name. */
    @ColumnInfo(name = "patient_ref") val patientRef: String,
    /** FALL_DETECTED, FALL_CONFIRMED, CARDIAC_FALL, MANUAL_SOS, ML_FALL, HEAT_ORANGE, HEAT_RED */
    val kind: String,
    /** device, phone_ml, phone */
    val source: String,
    val score: Float,
    @ColumnInfo(name = "hr_bpm") val hrBpm: Float,
    @ColumnInfo(name = "gait_deviation") val gaitDeviation: Float,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "acknowledged") val acknowledged: Boolean,
    @ColumnInfo(name = "sms_sent") val smsSent: Boolean,
    val note: String? = null,
) {
    /** Heat warnings share this table but are not falls and never page a caregiver. */
    val isHeat: Boolean get() = kind.startsWith("HEAT_")
    val isSos: Boolean get() = kind == "MANUAL_SOS"
    /** Counted explicitly: "everything but SOS" would count heat warnings as falls. */
    val isFall: Boolean get() = !isSos && !isHeat
}

/**
 * What the app has learned about one wearer (alert threshold history, resting heart-rate baseline).
 * Stored as JSON by ml/PersonalProfile; lives in the same SQLCipher database as everything else
 * and is deleted with the patient.
 */
@Entity(tableName = "personal_profile")
data class PersonalProfileRecord(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    val json: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One bucket of the daily histogram the risk panel draws. */
data class DayCount(
    @ColumnInfo(name = "day") val day: String,
    @ColumnInfo(name = "n") val n: Int,
)

/** Aggregates for the clinical report, computed in SQL so the whole
 *  history never has to be pulled into memory. */
data class VitalsSummary(
    @ColumnInfo(name = "samples") val samples: Int,
    @ColumnInfo(name = "avg_bpm") val avgBpm: Float,
    @ColumnInfo(name = "min_bpm") val minBpm: Float,
    @ColumnInfo(name = "max_bpm") val maxBpm: Float,
    @ColumnInfo(name = "avg_sdnn") val avgSdnn: Float,
    @ColumnInfo(name = "avg_rmssd") val avgRmssd: Float,
    @ColumnInfo(name = "avg_pnn50") val avgPnn50: Float,
    @ColumnInfo(name = "worn_pct") val wornPct: Float,
)

@Dao
interface VitalsDao {
    @Insert suspend fun insert(record: VitalsRecord)

    @Query("SELECT * FROM vitals ORDER BY recorded_at DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<VitalsRecord>>

    @Query(
        "SELECT * FROM vitals WHERE device_id = :deviceId " +
            "ORDER BY recorded_at DESC LIMIT :limit"
    )
    fun recentForDevice(deviceId: String, limit: Int = 500): Flow<List<VitalsRecord>>

    @Query("SELECT * FROM vitals WHERE recorded_at >= :since ORDER BY recorded_at ASC")
    suspend fun since(since: Long): List<VitalsRecord>

    @Query(
        "SELECT * FROM vitals WHERE device_id = :deviceId AND recorded_at >= :since " +
            "ORDER BY recorded_at ASC"
    )
    suspend fun sinceForDevice(deviceId: String, since: Long): List<VitalsRecord>

    @Query(
        """
        SELECT COUNT(*) AS samples,
               AVG(bpm) AS avg_bpm, MIN(bpm) AS min_bpm, MAX(bpm) AS max_bpm,
               AVG(sdnn_ms) AS avg_sdnn, AVG(rmssd_ms) AS avg_rmssd,
               AVG(pnn50_pct) AS avg_pnn50,
               100.0 * SUM(CASE WHEN worn THEN 1 ELSE 0 END) / COUNT(*) AS worn_pct
        FROM vitals
        WHERE device_id = :deviceId AND recorded_at >= :since AND bpm > 0
        """
    )
    suspend fun summary(deviceId: String, since: Long): VitalsSummary?

    /** The same aggregates over [from, to), for "change against the previous period". */
    @Query(
        """
        SELECT COUNT(*) AS samples,
               AVG(bpm) AS avg_bpm, MIN(bpm) AS min_bpm, MAX(bpm) AS max_bpm,
               AVG(sdnn_ms) AS avg_sdnn, AVG(rmssd_ms) AS avg_rmssd,
               AVG(pnn50_pct) AS avg_pnn50,
               100.0 * SUM(CASE WHEN worn THEN 1 ELSE 0 END) / COUNT(*) AS worn_pct
        FROM vitals
        WHERE device_id = :deviceId AND recorded_at >= :from AND recorded_at < :to AND bpm > 0
        """
    )
    suspend fun summaryBetween(deviceId: String, from: Long, to: Long): VitalsSummary?

    @Query(
        "SELECT MIN(battery_pct) FROM vitals " +
            "WHERE device_id = :deviceId AND recorded_at >= :since AND battery_pct > 0"
    )
    suspend fun minBattery(deviceId: String, since: Long): Int?

    @Query("DELETE FROM vitals WHERE recorded_at < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM vitals WHERE device_id = :deviceId")
    suspend fun purgeDevice(deviceId: String)
}

@Dao
interface EventDao {
    @Insert suspend fun insert(record: EventRecord): Long

    @Query("SELECT * FROM events ORDER BY occurred_at DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<EventRecord>>

    @Query(
        "SELECT * FROM events WHERE device_id = :deviceId " +
            "ORDER BY occurred_at DESC LIMIT :limit"
    )
    fun recentForDevice(deviceId: String, limit: Int = 200): Flow<List<EventRecord>>

    @Query(
        "SELECT * FROM events WHERE device_id = :deviceId AND occurred_at >= :since " +
            "ORDER BY occurred_at ASC"
    )
    suspend fun sinceForDevice(deviceId: String, since: Long): List<EventRecord>

    @Query("UPDATE events SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE events SET sms_sent = 1 WHERE id = :id")
    suspend fun markSmsSent(id: Long)

    @Query("UPDATE events SET note = :note WHERE id = :id")
    suspend fun annotate(id: Long, note: String)

    @Query("SELECT COUNT(*) FROM events WHERE occurred_at >= :since")
    suspend fun countSince(since: Long): Int

    @Query(
        "SELECT COUNT(*) FROM events WHERE device_id = :deviceId AND occurred_at >= :since"
    )
    suspend fun countSinceForDevice(deviceId: String, since: Long): Int

    // SQLite has no date type, so the epoch milliseconds are divided down to
    // seconds for strftime. unixepoch is not available on every Android
    // SQLite build, hence the explicit 'unixepoch' modifier form.
    @Query(
        """
        SELECT strftime('%Y-%m-%d', occurred_at / 1000, 'unixepoch') AS day,
               COUNT(*) AS n
        FROM events
        WHERE device_id = :deviceId AND occurred_at >= :since
        GROUP BY day ORDER BY day ASC
        """
    )
    suspend fun dailyCounts(deviceId: String, since: Long): List<DayCount>

    @Query("DELETE FROM events WHERE device_id = :deviceId")
    suspend fun purgeDevice(deviceId: String)
}

@Dao
interface PersonalProfileDao {
    @Query("SELECT * FROM personal_profile WHERE device_id = :deviceId")
    suspend fun get(deviceId: String): PersonalProfileRecord?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(record: PersonalProfileRecord)

    @Query("DELETE FROM personal_profile WHERE device_id = :deviceId")
    suspend fun delete(deviceId: String)
}

@Database(
    entities = [VitalsRecord::class, EventRecord::class, PersonalProfileRecord::class],
    version = 3,
    exportSchema = false,
)
abstract class EchoWearDatabase : RoomDatabase() {
    abstract fun vitals(): VitalsDao
    abstract fun events(): EventDao
    abstract fun profiles(): PersonalProfileDao

    companion object {
        @Volatile private var instance: EchoWearDatabase? = null

        fun get(context: Context): EchoWearDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personal_profile` (" +
                        "`device_id` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`device_id`))"
                )
            }
        }

        private fun build(context: Context): EchoWearDatabase {
            net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
            val passphrase = DatabaseKey.passphrase(context)
            val factory = SupportFactory(passphrase.toByteArray(Charsets.UTF_8))
            return Room.databaseBuilder(
                context, EchoWearDatabase::class.java, "echowear.db",
            ).openHelperFactory(factory)
                // v2 -> v3 only adds the personal_profile table, so existing
                // vitals and events are kept.
                .addMigrations(MIGRATION_2_3)
                // v1 rows had no device column, so there is nothing sensible
                // to migrate them to; a pre-release install is rebuilt empty.
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

/**
 * Generates a random database passphrase once, then stores it wrapped by an
 * AES key held in the Android hardware keystore. The plaintext passphrase is
 * never written to disk or to SharedPreferences.
 */
private object DatabaseKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "echowear_db_wrapping_key"
    private const val PREFS = "echowear_secure"
    private const val PREF_BLOB = "db_pass_blob"
    private const val PREF_IV = "db_pass_iv"

    fun passphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString(PREF_BLOB, null)
        val iv = prefs.getString(PREF_IV, null)
        if (blob != null && iv != null) {
            return unwrap(
                Base64.decode(blob, Base64.NO_WRAP),
                Base64.decode(iv, Base64.NO_WRAP),
            )
        }

        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val pass = Base64.encodeToString(fresh, Base64.NO_WRAP)
        val (wrapped, nonce) = wrap(pass)
        prefs.edit()
            .putString(PREF_BLOB, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(nonce, Base64.NO_WRAP))
            .apply()
        return pass
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately NOT requiring user authentication: the monitor
                // service has to open the database while the phone is locked.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plain: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8)) to cipher.iv
    }

    private fun unwrap(wrapped: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(wrapped), Charsets.UTF_8)
    }
}
