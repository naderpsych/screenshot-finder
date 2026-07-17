package com.nader.screenfinder.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "shots")
data class Shot(
    @PrimaryKey val id: Long,
    val name: String,
    val date: Long,
    val sourceApp: String? = null,
    val text: String? = null,
    val norm: String? = null,
    val labels: String? = null,
    val category: String? = null,
    val source: String? = null,
    val scanned: Boolean = false,
    val clipDone: Boolean = false
)

@Fts4(contentEntity = Shot::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "shots_fts")
data class ShotFts(
    val norm: String?,
    val labels: String?,
    val source: String?,
    val name: String
)

@Entity(tableName = "rules")
data class UserRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val keywords: String
)

data class CatCount(val category: String?, val cnt: Int)

@Dao
interface ShotDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(s: List<Shot>)

    @Update
    suspend fun update(s: Shot)

    @Query("SELECT id FROM shots")
    suspend fun allIds(): List<Long>

    @Query("DELETE FROM shots WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM shots WHERE scanned=0 ORDER BY date DESC LIMIT :n")
    suspend fun unscanned(n: Int): List<Shot>

    @Query("SELECT COUNT(*) FROM shots")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM shots WHERE scanned=1")
    suspend fun countScanned(): Int

    @Query("SELECT * FROM shots ORDER BY date DESC LIMIT 300")
    suspend fun recent(): List<Shot>

    @Query("SELECT * FROM shots WHERE category=:c ORDER BY date DESC LIMIT 1000")
    suspend fun byCategory(c: String): List<Shot>

    @Query("SELECT s.* FROM shots s JOIN shots_fts f ON s.id=f.rowid WHERE shots_fts MATCH :q ORDER BY s.date DESC LIMIT 500")
    suspend fun search(q: String): List<Shot>

    @Query("SELECT category, COUNT(*) cnt FROM shots WHERE scanned=1 GROUP BY category ORDER BY cnt DESC")
    suspend fun cats(): List<CatCount>

    @Query("SELECT * FROM rules")
    suspend fun rules(): List<UserRule>

    @Insert
    suspend fun addRule(r: UserRule)

    @Query("SELECT * FROM shots WHERE scanned=1")
    suspend fun allScanned(): List<Shot>

    @Query("SELECT * FROM shots WHERE scanned=1 AND clipDone=0 ORDER BY date DESC LIMIT :n")
    suspend fun needClip(n: Int): List<Shot>

    @Query("UPDATE shots SET category=:cat WHERE scanned=1 AND norm LIKE '%' || :kw || '%'")
    suspend fun applyRule(cat: String, kw: String): Int

    @Query("SELECT source FROM shots WHERE scanned=1 AND source IS NOT NULL GROUP BY source HAVING COUNT(*) >= :min")
    suspend fun bigSources(min: Int): List<String>

    @Query("UPDATE shots SET category='כתבות · ' || :src WHERE source=:src AND category='כתבות'")
    suspend fun refineArticles(src: String): Int

    @Query("UPDATE shots SET category=:src WHERE source=:src AND category='לא מסווג'")
    suspend fun adoptSource(src: String): Int

    @Query("SELECT labels FROM shots WHERE category='לא מסווג' AND labels IS NOT NULL AND labels != ''")
    suspend fun unclassifiedLabels(): List<String>

    @Query("UPDATE shots SET category=:tag WHERE category='לא מסווג' AND labels LIKE '%' || :tag || '%'")
    suspend fun adoptTag(tag: String): Int

    @Query("SELECT * FROM shots WHERE category=:c AND scanned=1")
    suspend fun allInCategory(c: String): List<Shot>

    @Query("SELECT * FROM shots WHERE category='לא מסווג' AND scanned=1 AND text IS NOT NULL AND text != '' AND (labels IS NULL OR labels NOT LIKE '%🧠%') ORDER BY date DESC LIMIT :n")
    suspend fun needBrain(n: Int): List<Shot>
}

@Database(entities = [Shot::class, ShotFts::class, UserRule::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): ShotDao

    companion object {
        private val M12 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN clipDone INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "shots.db")
                .addMigrations(M12)
                .build().also { inst = it }
        }
    }
}
