package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NoiseDao {
    @Query("SELECT * FROM noise_records WHERE deviceEventId = :eventId LIMIT 1")
    suspend fun findByDeviceEventId(eventId: String): NoiseRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeviceEventOnce(record: NoiseRecord): Long

    @Transaction
    suspend fun persistDeviceEvent(record: NoiseRecord): NoiseRecord {
        val eventId = requireNotNull(record.deviceEventId)
        findByDeviceEventId(eventId)?.let { return it }
        val id = insertDeviceEventOnce(record)
        return if (id >= 0) record.copy(id = id.toInt())
        else requireNotNull(findByDeviceEventId(eventId))
    }

    @Query("SELECT * FROM noise_records WHERE deviceClassificationPending = 1 AND recordingPath IS NOT NULL ORDER BY id LIMIT 16")
    suspend fun pendingDeviceClassifications(): List<NoiseRecord>

    @Query("SELECT * FROM noise_records WHERE deviceClassificationPending = 1 AND recordingPath IS NOT NULL AND id > :afterId ORDER BY id LIMIT 16")
    suspend fun pendingDeviceClassificationsAfter(afterId: Int): List<NoiseRecord>

    @Query("UPDATE noise_records SET classificationLabel = :label, classificationConfidence = :confidence, isInterfloorCandidate = :candidate, deviceClassificationPending = 0, syncState = CASE WHEN userId IS NULL THEN syncState ELSE 'PENDING' END WHERE id = :id")
    suspend fun finishDeviceClassification(id: Int, label: String, confidence: Double, candidate: Boolean)

    @Query("UPDATE noise_records SET classificationLabel = :label, classificationConfidence = :confidence, isInterfloorCandidate = :candidate, soundAnalysisJson = :analysisJson, deviceClassificationPending = 0, syncState = CASE WHEN userId IS NULL THEN syncState ELSE 'PENDING' END WHERE id = :id")
    suspend fun finishDeviceClassificationWithAnalysis(id: Int, label: String, confidence: Double, candidate: Boolean, analysisJson: String)

    @Transaction
    suspend fun mergeRemoteRecord(remote: NoiseRecord) {
        val remoteSync = remote.syncId ?: return
        val existing = findBySyncId(remoteSync)
            ?: remote.deviceEventId?.let { findByDeviceEventId(it) }
        // A second client can assign another sync UUID to the SAME physical event.
        // Keep the original receipt, owner and local file; never resolve this via REPLACE.
        if (existing != null && (existing.userId != remote.userId || existing.syncId != remoteSync)) return
        // Local pending results must be uploaded before accepting an older cloud row.
        if (existing != null && existing.syncState != "SYNCED") return
        if (existing == null) {
            insertRecord(remote.copy(id = 0))
        } else {
            updateRecord(remote.copy(
                id = existing.id,
                userId = existing.userId,
                syncId = existing.syncId,
                deviceEventId = existing.deviceEventId ?: remote.deviceEventId,
                recordingPath = existing.recordingPath,
                remoteAudioPath = remote.remoteAudioPath ?: existing.remoteAudioPath
            ))
        }
    }

    @Query("SELECT * FROM noise_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: Int): NoiseRecord?

    @Query("UPDATE noise_records SET soundAnalysisJson = :json, syncState = CASE WHEN userId IS NULL THEN syncState ELSE 'PENDING' END WHERE id = :id AND userId IS :userId")
    suspend fun saveSoundAnalysis(id: Int, userId: String?, json: String): Int

    @Query("UPDATE noise_records SET note = :note, syncState = CASE WHEN userId IS NULL THEN syncState ELSE 'PENDING' END WHERE id = :id")
    suspend fun updateRecordNote(id: Int, note: String)

    @Query("UPDATE noise_records SET remoteAudioPath = :path, syncState = 'SYNCED' WHERE id = :id AND soundAnalysisJson IS :analysisJson")
    suspend fun markSynced(id: Int, path: String?, analysisJson: String?)

    @Query("UPDATE noise_records SET recordingPath = :path WHERE id = :id")
    suspend fun setRecordingPath(id: Int, path: String)

    @Query("SELECT * FROM noise_records WHERE userId = :userId ORDER BY timestamp DESC")
    fun getAllRecordsForUser(userId: String): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE userId IS NULL ORDER BY timestamp DESC")
    fun getAnonymousRecords(): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE userId = :userId AND isExceeded = 1 ORDER BY timestamp DESC")
    fun getExceededRecordsForUser(userId: String): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE userId IS NULL AND isExceeded = 1 ORDER BY timestamp DESC")
    fun getAnonymousExceededRecords(): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE userId = :userId AND (recordingPath IS NOT NULL OR remoteAudioPath IS NOT NULL) ORDER BY timestamp DESC")
    fun getWithRecordingsForUser(userId: String): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE userId IS NULL AND (recordingPath IS NOT NULL OR remoteAudioPath IS NOT NULL) ORDER BY timestamp DESC")
    fun getAnonymousWithRecordings(): Flow<List<NoiseRecord>>

    @Query("SELECT * FROM noise_records WHERE userId IS NULL ORDER BY timestamp DESC")
    suspend fun getAnonymousRecordsOnce(): List<NoiseRecord>

    @Query("SELECT * FROM noise_records WHERE userId = :userId ORDER BY timestamp DESC")
    suspend fun getRecordsForUserOnce(userId: String): List<NoiseRecord>

    @Query("SELECT * FROM noise_records WHERE syncId = :syncId LIMIT 1")
    suspend fun findBySyncId(syncId: String): NoiseRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: NoiseRecord): Long

    @Query("DELETE FROM noise_records WHERE id = :id")
    suspend fun deleteRecordById(id: Int)

    @Query("DELETE FROM noise_records WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    @Query("DELETE FROM noise_records WHERE userId IS NULL")
    suspend fun clearAnonymous()

    @Update
    suspend fun updateRecord(record: NoiseRecord)

    @Query("DELETE FROM noise_records")
    suspend fun clearAll()
}
