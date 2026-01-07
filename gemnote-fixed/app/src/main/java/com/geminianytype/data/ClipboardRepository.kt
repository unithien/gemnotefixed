package com.geminianytype.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardRepository @Inject constructor(
    private val clipboardDao: ClipboardDao,
    private val anytypeApi: AnytypeApi
) {
    fun getAllClipboardEntries(): Flow<List<ClipboardEntry>> = clipboardDao.getAllEntries()
    
    suspend fun saveClipboardEntry(content: String): Long {
        val preview = content.take(100).replace("\n", " ")
        return clipboardDao.insert(ClipboardEntry(content = content, preview = preview))
    }
    
    suspend fun deleteEntry(entry: ClipboardEntry) = clipboardDao.delete(entry)
    suspend fun clearAllEntries() = clipboardDao.deleteAll()
    
    suspend fun getSpaces(): Result<List<Space>> = try {
        val response = anytypeApi.getSpaces()
        if (response.isSuccessful) Result.success(response.body()?.data ?: emptyList())
        else Result.failure(Exception("Failed: ${response.code()}"))
    } catch (e: Exception) { Result.failure(e) }
    
    suspend fun createNote(spaceId: String, title: String, content: String): Result<AnytypeObject> = try {
        val request = CreateObjectRequest(name = title, body = content)
        val response = anytypeApi.createObject(spaceId, request)
        if (response.isSuccessful && response.body()?.anytypeObject != null)
            Result.success(response.body()!!.anytypeObject!!)
        else Result.failure(Exception("Failed: ${response.code()}"))
    } catch (e: Exception) { Result.failure(e) }
    
    suspend fun markAsSynced(id: Long, objectId: String) = clipboardDao.markAsSynced(id, objectId)
}
