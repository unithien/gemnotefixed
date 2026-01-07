package com.geminianytype.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clipboard_entries")
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val preview: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val anytypeObjectId: String? = null
)

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<ClipboardEntry>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry): Long
    
    @Delete
    suspend fun delete(entry: ClipboardEntry)
    
    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()
    
    @Query("UPDATE clipboard_entries SET isSynced = 1, anytypeObjectId = :objectId WHERE id = :id")
    suspend fun markAsSynced(id: Long, objectId: String)
}

@Database(entities = [ClipboardEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao
}
