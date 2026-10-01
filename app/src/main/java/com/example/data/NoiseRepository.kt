package com.example.data

import com.example.auth.AccountSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NoiseRepository(
    private val noiseDao: NoiseDao,
    private val syncManager: FirebaseSyncManager? = null
) {
    val allRecords: Flow<List<NoiseRecord>> = AccountSession.userId.flatMapLatest { userId ->
        if (userId == null) noiseDao.getAnonymousRecords() else noiseDao.getAllRecordsForUser(userId)
    }

    val exceededRecords: Flow<List<NoiseRecord>> = AccountSession.userId.flatMapLatest { userId ->
        if (userId == null) {
            noiseDao.getAnonymousExceededRecords()
        } else {
            noiseDao.getExceededRecordsForUser(userId)
        }
    }

    val recordingsList: Flow<List<NoiseRecord>> = AccountSession.userId.flatMapLatest { userId ->
        if (userId == null) {
            noiseDao.getAnonymousWithRecordings()
        } else {
            noiseDao.getWithRecordingsForUser(userId)
        }
    }

    suspend fun insert(record: NoiseRecord): Long {
        val normalized = record.copy(
            userId = record.userId ?: AccountSession.userId.value,
            syncId = record.syncId ?: UUID.randomUUID().toString(),
            syncState = if (AccountSession.userId.value == null) "LOCAL" else "PENDING"
        )
        val insertedId = noiseDao.insertRecord(normalized)
        val persisted = normalized.copy(id = insertedId.toInt())
        if (persisted.id != normalized.id) noiseDao.updateRecord(persisted)
        syncManager?.enqueue(persisted)
        return insertedId
    }

    suspend fun delete(record: NoiseRecord) {
        syncManager?.deleteRemote(record)
        noiseDao.deleteRecordById(record.id)
    }

    suspend fun findById(id: Int): NoiseRecord? = noiseDao.findById(id)

    suspend fun saveSoundAnalysis(id: Int, userId: String?, json: String) {
        if (noiseDao.saveSoundAnalysis(id, userId, json) == 0) return
        noiseDao.findById(id)?.let { syncManager?.enqueue(it) }
    }

    suspend fun update(record: NoiseRecord) {
        val updated = record.copy(
            syncState = if (record.userId != null) "PENDING" else record.syncState
        )
        noiseDao.updateRecord(updated)
        syncManager?.enqueue(updated)
    }

    suspend fun clear() {
        val userId = AccountSession.userId.value
        if (userId == null) {
            noiseDao.clearAnonymous()
        } else {
            syncManager?.deleteAllCurrentUserRecords()
            noiseDao.clearForUser(userId)
        }
    }
}
