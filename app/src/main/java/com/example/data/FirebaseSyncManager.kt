package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.auth.AccountSession
import com.example.auth.FirebaseClientProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

private const val USERS_COLLECTION = "users"
private const val RECORDS_COLLECTION = "noise_records"
private const val STORAGE_ROOT = "noise-recordings"

/** Offline-first synchronization between Room and each Firebase user account. */
class FirebaseSyncManager private constructor(
    private val appContext: Context,
    private val noiseDao: NoiseDao
) {
    companion object {
        @Volatile
        private var INSTANCE: FirebaseSyncManager? = null

        fun getInstance(context: Context): FirebaseSyncManager {
            val appContext = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseSyncManager(
                    appContext,
                    AppDatabase.getDatabase(appContext).noiseDao()
                ).also { INSTANCE = it }
            }
        }
    }

    private val firestore: FirebaseFirestore?
        get() = FirebaseClientProvider.firestoreOrNull(appContext)
    private val storage: FirebaseStorage?
        get() = FirebaseClientProvider.storageOrNull(appContext)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    fun enqueue(record: NoiseRecord) {
        if (firestore == null || record.userId == null) return
        syncScope.launch {
            runCatching { syncMutex.withLock { syncOne(record) } }
                .onFailure { error ->
                    Log.w("FirebaseSync", "기록 동기화에 실패했습니다.", error)
                    markSyncError(record)
                }
        }
    }

    suspend fun saveUserProfile(user: FirebaseUser) {
        val db = firestore ?: return
        val profile = mapOf(
            "uid" to user.uid,
            "email" to user.email,
            "display_name" to user.displayName,
            "photo_url" to user.photoUrl?.toString(),
            "updated_at" to FieldValue.serverTimestamp()
        )
        runCatching {
            db.collection(USERS_COLLECTION)
                .document(user.uid)
                .set(profile, SetOptions.merge())
                .await()
        }.onFailure { error ->
            Log.w("FirebaseSync", "사용자 프로필 저장에 실패했습니다.", error)
        }
    }

    suspend fun syncCurrentUser(): Result<Int> = runCatching {
        syncMutex.withLock { syncCurrentUserLocked() }
    }

    private suspend fun syncCurrentUserLocked(): Int {
        val db = firestore ?: return 0
        val userId = AccountSession.userId.value ?: return 0

        // 로그인 전 생성된 기록은 이 기기에서 처음 로그인한 계정에 귀속한다.
        noiseDao.getAnonymousRecordsOnce().forEach { record ->
            noiseDao.updateRecord(
                record.copy(
                    userId = userId,
                    syncId = record.syncId ?: UUID.randomUUID().toString(),
                    syncState = "PENDING"
                )
            )
        }

        val remoteRows = records(db, userId).get().await().documents
        remoteRows.forEach { mergeRemoteRow(userId, it) }

        noiseDao.getRecordsForUserOnce(userId)
            .filter {
                it.syncState != "SYNCED" ||
                    (it.remoteAudioPath == null && it.recordingPath != null)
            }
            .forEach { syncOne(it) }

        return remoteRows.size
    }

    private suspend fun mergeRemoteRow(userId: String, document: DocumentSnapshot) {
        val remote = document.toNoiseRecord(userId) ?: return
        noiseDao.mergeRemoteRecord(remote)
    }

    private suspend fun syncOne(requestedRecord: NoiseRecord) {
        val db = firestore ?: return
        val record = noiseDao.findById(requestedRecord.id) ?: return
        val userId = record.userId ?: return
        if (AccountSession.userId.value != userId) return

        val syncId = record.syncId ?: UUID.randomUUID().toString()
        val ownedRecord = record.copy(
            userId = userId,
            syncId = syncId,
            syncState = "PENDING"
        )
        if (ownedRecord.syncId != record.syncId || ownedRecord.userId != record.userId) {
            noiseDao.updateRecord(ownedRecord)
        }

        val remoteAudioPath = uploadAudioIfAvailable(ownedRecord)
        val current = noiseDao.findById(ownedRecord.id) ?: return
        records(db, userId)
            .document(syncId)
            .set(current.toCloudMap(remoteAudioPath), SetOptions.merge())
            .await()

        noiseDao.markSynced(current.id, remoteAudioPath ?: current.remoteAudioPath, current.soundAnalysisJson)
    }

    private suspend fun uploadAudioIfAvailable(record: NoiseRecord): String? {
        val firebaseStorage = storage ?: return record.remoteAudioPath
        val userId = record.userId ?: return record.remoteAudioPath
        val localPath = record.recordingPath ?: return record.remoteAudioPath
        val localFile = File(localPath)
        if (!localFile.exists()) return record.remoteAudioPath

        val extension = localFile.extension.ifBlank { "audio" }
        val remotePath = "$STORAGE_ROOT/$userId/${record.syncId}.$extension"
        return runCatching {
            firebaseStorage.reference.child(remotePath)
                .putFile(Uri.fromFile(localFile))
                .await()
            remotePath
        }.onFailure { error ->
            Log.w("FirebaseSync", "녹음 파일 업로드에 실패했습니다.", error)
        }.getOrNull() ?: record.remoteAudioPath
    }

    suspend fun downloadAudio(record: NoiseRecord): String? {
        val firebaseStorage = storage ?: return null
        val remotePath = record.remoteAudioPath ?: return null
        val syncId = record.syncId ?: return null
        val extension = remotePath.substringAfterLast('.', "audio")
        val destination = File(appContext.filesDir, "cloud_$syncId.$extension")
        firebaseStorage.reference.child(remotePath).getFile(destination).await()
        noiseDao.setRecordingPath(record.id, destination.absolutePath)
        return destination.absolutePath
    }

    suspend fun deleteRemote(record: NoiseRecord) {
        val db = firestore ?: return
        val userId = record.userId ?: return
        val syncId = record.syncId ?: return
        if (AccountSession.userId.value != userId) return

        runCatching {
            records(db, userId).document(syncId).delete().await()
        }.onFailure { error ->
            Log.w("FirebaseSync", "원격 기록 삭제에 실패했습니다.", error)
        }

        record.remoteAudioPath?.let { path ->
            runCatching { storage?.reference?.child(path)?.delete()?.await() }
                .onFailure { error ->
                    Log.w("FirebaseSync", "원격 녹음 파일 삭제에 실패했습니다.", error)
                }
        }
    }

    suspend fun deleteAllCurrentUserRecords() {
        val db = firestore ?: return
        val userId = AccountSession.userId.value ?: return
        val snapshot = runCatching { records(db, userId).get().await() }
            .onFailure { Log.w("FirebaseSync", "계정 기록 조회에 실패했습니다.", it) }
            .getOrNull() ?: return

        snapshot.documents.forEach { document ->
            document.getString("remote_audio_path")?.let { path ->
                runCatching { storage?.reference?.child(path)?.delete()?.await() }
            }
            runCatching { document.reference.delete().await() }
        }
    }

    private suspend fun markSyncError(record: NoiseRecord) {
        val current = noiseDao.findBySyncId(record.syncId ?: return) ?: return
        noiseDao.updateRecord(current.copy(syncState = "ERROR"))
    }

    private fun records(db: FirebaseFirestore, userId: String) =
        db.collection(USERS_COLLECTION)
            .document(userId)
            .collection(RECORDS_COLLECTION)
}

private fun NoiseRecord.toCloudMap(remoteAudioPath: String?): Map<String, Any?> = mapOf(
    "user_id" to requireNotNull(userId),
    "sync_id" to requireNotNull(syncId),
    "timestamp_ms" to timestamp,
    "max_db" to maxDb,
    "avg_db" to avgDb,
    "max_vibration" to maxVibration,
    "is_exceeded" to isExceeded,
    "is_noise_exceeded" to isNoiseExceeded,
    "is_vibe_exceeded" to isVibeExceeded,
    "duration_ms" to durationMs,
    "note" to note,
    "recording_source" to recordingSource,
    "classification_label" to classificationLabel,
    "classification_confidence" to classificationConfidence,
    "is_interfloor_candidate" to isInterfloorCandidate,
    "sound_analysis_json" to soundAnalysisJson,
    "remote_audio_path" to remoteAudioPath,
    "updated_at" to FieldValue.serverTimestamp()
)

private fun DocumentSnapshot.toNoiseRecord(userId: String): NoiseRecord? {
    val syncId = getString("sync_id") ?: id.takeIf { it.isNotBlank() } ?: return null
    return NoiseRecord(
        timestamp = getLong("timestamp_ms") ?: 0L,
        maxDb = getDouble("max_db") ?: 0.0,
        avgDb = getDouble("avg_db") ?: 0.0,
        maxVibration = getDouble("max_vibration") ?: 0.0,
        isExceeded = getBoolean("is_exceeded") ?: false,
        isNoiseExceeded = getBoolean("is_noise_exceeded") ?: false,
        isVibeExceeded = getBoolean("is_vibe_exceeded") ?: false,
        recordingPath = null,
        durationMs = getLong("duration_ms") ?: 0L,
        note = getString("note") ?: "",
        recordingSource = getString("recording_source") ?: "PHONE",
        classificationLabel = getString("classification_label") ?: "unclassified",
        classificationConfidence = getDouble("classification_confidence") ?: 0.0,
        isInterfloorCandidate = getBoolean("is_interfloor_candidate") ?: false,
        soundAnalysisJson = getString("sound_analysis_json"),
        userId = userId,
        syncId = syncId,
        remoteAudioPath = getString("remote_audio_path"),
        syncState = "SYNCED"
    )
}
