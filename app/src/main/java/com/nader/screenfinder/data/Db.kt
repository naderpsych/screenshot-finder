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
    val scanned: Boolean = false
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
}

@Database(entities = [Shot::class, ShotFts::class, UserRule::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): ShotDao

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "shots.db")
                .build().also { inst = it }
        }
    }
}
