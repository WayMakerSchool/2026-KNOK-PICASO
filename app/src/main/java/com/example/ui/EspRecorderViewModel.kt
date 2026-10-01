package com.example.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.EspRecorderClient
import com.example.data.EspRecorderStatus
import com.example.data.NoiseRecord
import com.example.data.NoiseRepository
import com.example.data.FirebaseSyncManager
import com.example.ml.AudioClassificationResult
import com.example.ml.AudioClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class EspRecorderConnectionState {
    DISCONNECTED,
    CHECKING,
    CONNECTED,
    RECORDING
}

enum class EspAlwaysRecordingState {
    OFF,
    CHECKING,
    MONITORING,
    RECORDING,
    GRACE_PERIOD,
    SAVING,
    ERROR
}

/**
 * Owns the ESP32 recorder session and imports its final WAV into the app's
 * existing recordings database.
 */
class EspRecorderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val DEFAULT_BASE_URL = "http://knok-esp32.local"
        const val AUTO_TRIGGER_DB = 90.0
        const val AUTO_RECORD_DURATION_MS = 5_000L
        const val AUTO_GRACE_PERIOD_MS = 3_000L
        private const val PREFS_NAME = "esp32_recorder"
        private const val BASE_URL_KEY = "base_url"
    }

    private val client = EspRecorderClient()
    private val repository = NoiseRepository(
        AppDatabase.getDatabase(application).noiseDao(),
        FirebaseSyncManager.getInstance(application)
    )
    private val preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioClassifier: AudioClassifier? = runCatching {
        AudioClassifier(application)
    }.onFailure { error ->
        Log.w("EspRecorderViewModel", "오디오 분류기를 불러오지 못했습니다.", error)
    }.getOrNull()

    private val _baseUrl = MutableStateFlow(
        preferences.getString(BASE_URL_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    )
    val baseUrl = _baseUrl.asStateFlow()

    private val _connectionState = MutableStateFlow(EspRecorderConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _status = MutableStateFlow<EspRecorderStatus?>(null)
    val status = _status.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _message = MutableStateFlow("ESP32 주소를 입력하고 연결을 확인해 주세요.")
    val message = _message.asStateFlow()

    private val _alwaysRecordingEnabled = MutableStateFlow(false)
    val alwaysRecordingEnabled = _alwaysRecordingEnabled.asStateFlow()

    private val _alwaysRecordingState = MutableStateFlow(EspAlwaysRecordingState.OFF)
    val alwaysRecordingState = _alwaysRecordingState.asStateFlow()

    private val _currentLevelDb = MutableStateFlow(0.0)
    val currentLevelDb = _currentLevelDb.asStateFlow()

    private var pollingJob: Job? = null
    private var operationJob: Job? = null
    private var alwaysMonitoringJob: Job? = null

    fun setBaseUrl(value: String) {
        _baseUrl.value = value
        preferences.edit().putString(BASE_URL_KEY, value.trim()).apply()
    }

    fun checkConnection() {
        if (_isBusy.value) return
        if (_alwaysRecordingEnabled.value) {
            _message.value = "상시 녹음 중에는 상시 녹음 탭에서 상태를 확인합니다."
            return
        }

        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            _isBusy.value = true
            _connectionState.value = EspRecorderConnectionState.CHECKING
            try {
                val currentStatus = client.getStatus(_baseUrl.value)
                applyStatus(currentStatus)
                _message.value = connectedMessage(currentStatus)
                if (currentStatus.recording) startPolling()
            } catch (error: Exception) {
                _connectionState.value = EspRecorderConnectionState.DISCONNECTED
                _message.value = error.message ?: "ESP32에 연결할 수 없습니다."
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun startRecording() {
        if (_isBusy.value || _connectionState.value == EspRecorderConnectionState.RECORDING) return
        if (_alwaysRecordingEnabled.value) {
            _message.value = "상시 녹음 중에는 수동 녹음을 시작할 수 없습니다."
            return
        }

        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            _isBusy.value = true
            _connectionState.value = EspRecorderConnectionState.CHECKING
            try {
                val startedStatus = client.startRecording(_baseUrl.value)
                applyStatus(startedStatus)
                _message.value = "ESP32 외장 마이크 녹음이 시작되었습니다."
                startPolling()
            } catch (error: Exception) {
                _connectionState.value = EspRecorderConnectionState.DISCONNECTED
                _message.value = error.message ?: "ESP32 녹음을 시작할 수 없습니다."
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun stopRecording(note: String = "") {
        if (_isBusy.value || _connectionState.value != EspRecorderConnectionState.RECORDING) return
        if (_alwaysRecordingEnabled.value) {
            _message.value = "상시 녹음 탭에서 상시 녹음을 중지해 주세요."
            return
        }

        pollingJob?.cancel()
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            _isBusy.value = true
            try {
                val lastKnownDuration = _status.value?.durationMs ?: 0L
                val stoppedStatus = client.stopRecording(_baseUrl.value)
                val durationMs = maxOf(lastKnownDuration, stoppedStatus.durationMs)
                applyStatus(stoppedStatus)
                saveLatestRecording(
                    status = stoppedStatus.copy(durationMs = durationMs),
                    note = note
                )
                _message.value = "ESP32 WAV를 앱에 저장했습니다. 녹음 보관함에서 재생할 수 있습니다."
            } catch (error: Exception) {
                _message.value = error.message ?: "ESP32 녹음 저장에 실패했습니다."
            } finally {
                _isBusy.value = false
                _connectionState.value = if (_status.value?.wifiConnected != false) {
                    EspRecorderConnectionState.CONNECTED
                } else {
                    EspRecorderConnectionState.DISCONNECTED
                }
            }
        }
    }

    fun startAlwaysRecording() {
        if (_alwaysRecordingEnabled.value || _isBusy.value) return
        if (_connectionState.value == EspRecorderConnectionState.RECORDING) {
            _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
            _message.value = "ESP32가 이미 수동 녹음 중입니다. 먼저 수동 녹음을 저장해 주세요."
            return
        }

        alwaysMonitoringJob?.cancel()
        _alwaysRecordingEnabled.value = true
        _alwaysRecordingState.value = EspAlwaysRecordingState.CHECKING

        alwaysMonitoringJob = viewModelScope.launch {
            var autoRecordingStartedAt = 0L
            var gracePeriodUntil = 0L
            var consecutiveErrors = 0

            try {
                while (isActive) {
                    val currentStatus = try {
                        client.getStatus(_baseUrl.value)
                    } catch (error: Exception) {
                        if (!_alwaysRecordingEnabled.value &&
                            _alwaysRecordingState.value != EspAlwaysRecordingState.RECORDING
                        ) {
                            _alwaysRecordingState.value = EspAlwaysRecordingState.OFF
                            _message.value = "상시 녹음을 중지했습니다."
                            return@launch
                        }

                        consecutiveErrors += 1
                        _message.value = "ESP32 상태 재확인 중: ${error.message ?: "네트워크 오류"}"
                        if (consecutiveErrors >= 10) {
                            if (_alwaysRecordingEnabled.value) {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                _message.value = "상시 녹음 연결이 끊겼습니다. Wi-Fi 주소와 연결 상태를 확인해 주세요."
                            } else {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.OFF
                                _message.value = "ESP32 상태를 확인하지 못해 상시 녹음을 중지했습니다."
                            }
                            return@launch
                        }
                        delay(500)
                        continue
                    }

                    consecutiveErrors = 0
                    applyStatus(currentStatus)
                    val now = SystemClock.elapsedRealtime()

                    if (!_alwaysRecordingEnabled.value) {
                        if (_alwaysRecordingState.value == EspAlwaysRecordingState.RECORDING &&
                            (currentStatus.recording || currentStatus.bytesWritten > 44L)
                        ) {
                            _alwaysRecordingState.value = EspAlwaysRecordingState.SAVING
                            _isBusy.value = true
                            try {
                                val completedStatus = if (currentStatus.recording) {
                                    client.stopRecording(_baseUrl.value)
                                } else {
                                    currentStatus
                                }
                                val elapsedDuration = (now - autoRecordingStartedAt)
                                    .coerceIn(0L, AUTO_RECORD_DURATION_MS)
                                val durationMs = maxOf(
                                    elapsedDuration,
                                    currentStatus.durationMs,
                                    completedStatus.durationMs
                                )
                                applyStatus(completedStatus)
                                saveLatestRecording(
                                    status = completedStatus.copy(durationMs = durationMs),
                                    note = "상시 녹음 중지"
                                )
                                _message.value = "상시 녹음 파일을 저장했습니다."
                            } finally {
                                _isBusy.value = false
                            }
                        }
                        _alwaysRecordingState.value = EspAlwaysRecordingState.OFF
                        if (!_message.value.contains("저장")) {
                            _message.value = "상시 녹음을 중지했습니다."
                        }
                        return@launch
                    }

                    when (_alwaysRecordingState.value) {
                        EspAlwaysRecordingState.CHECKING -> {
                            when {
                                !currentStatus.wifiConnected -> {
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                    _message.value = "ESP32 Wi-Fi가 연결되지 않았습니다."
                                    return@launch
                                }

                                !currentStatus.levelMeterAvailable -> {
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                    _message.value = "ESP32에 새 녹음 펌웨어를 업로드해 주세요. 현재 펌웨어는 dB 감지를 지원하지 않습니다."
                                    return@launch
                                }

                                currentStatus.recording -> {
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                    _message.value = "ESP32가 이미 녹음 중입니다. 수동 녹음을 먼저 중지해 주세요."
                                    return@launch
                                }

                                else -> {
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.MONITORING
                                    _message.value = "ESP 마이크 음량 감지 중 · ${currentStatus.levelDb.toInt()} dB"
                                }
                            }
                        }

                        EspAlwaysRecordingState.MONITORING -> {
                            if (!currentStatus.wifiConnected) {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                _message.value = "ESP32 Wi-Fi 연결이 끊겼습니다."
                                return@launch
                            }
                            if (!currentStatus.levelMeterAvailable) {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                _message.value = "ESP32 새 펌웨어가 필요합니다. dB 감지 응답이 없습니다."
                                return@launch
                            }
                            if (currentStatus.recording) {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                _message.value = "다른 녹음 세션이 감지되어 상시 녹음을 멈췄습니다."
                                return@launch
                            }

                            if (currentStatus.levelDb >= AUTO_TRIGGER_DB) {
                                _isBusy.value = true
                                try {
                                    val startedStatus = client.startRecording(
                                        baseUrl = _baseUrl.value,
                                        durationMs = AUTO_RECORD_DURATION_MS
                                    )
                                    if (!startedStatus.recording) {
                                        throw IllegalStateException("ESP32 녹음 시작 응답이 올바르지 않습니다.")
                                    }
                                    applyStatus(startedStatus)
                                    autoRecordingStartedAt = SystemClock.elapsedRealtime()
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.RECORDING
                                    _message.value = "${AUTO_TRIGGER_DB.toInt()} dB 감지 · 5초 녹음 중"
                                } finally {
                                    _isBusy.value = false
                                }
                            } else {
                                _message.value = "ESP 마이크 감지 중 · ${currentStatus.levelDb.toInt()} dB / ${AUTO_TRIGGER_DB.toInt()} dB"
                            }
                        }

                        EspAlwaysRecordingState.RECORDING -> {
                            val elapsed = (now - autoRecordingStartedAt).coerceAtLeast(0L)
                            if (!currentStatus.recording) {
                                if (currentStatus.bytesWritten > 44L) {
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.SAVING
                                    _isBusy.value = true
                                    try {
                                        val durationMs = maxOf(
                                            elapsed.coerceAtMost(AUTO_RECORD_DURATION_MS),
                                            currentStatus.durationMs
                                        )
                                        saveLatestRecording(
                                            status = currentStatus.copy(durationMs = durationMs),
                                            note = "${AUTO_TRIGGER_DB.toInt()}dB 자동 감지"
                                        )
                                        _message.value = "자동 녹음 5초 WAV를 저장했습니다."
                                    } finally {
                                        _isBusy.value = false
                                    }
                                    gracePeriodUntil = now + AUTO_GRACE_PERIOD_MS
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.GRACE_PERIOD
                                } else {
                                    _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                                    _message.value = "ESP32 녹음이 종료되었지만 WAV 파일을 찾지 못했습니다."
                                    return@launch
                                }
                            } else if (elapsed >= AUTO_RECORD_DURATION_MS) {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.SAVING
                                _isBusy.value = true
                                try {
                                    val stoppedStatus = client.stopRecording(_baseUrl.value)
                                    val durationMs = maxOf(
                                        elapsed.coerceAtMost(AUTO_RECORD_DURATION_MS),
                                        currentStatus.durationMs,
                                        stoppedStatus.durationMs
                                    )
                                    applyStatus(stoppedStatus)
                                    saveLatestRecording(
                                        status = stoppedStatus.copy(durationMs = durationMs),
                                        note = "${AUTO_TRIGGER_DB.toInt()}dB 자동 감지"
                                    )
                                    _message.value = "자동 녹음 5초 WAV를 저장했습니다."
                                } finally {
                                    _isBusy.value = false
                                }
                                gracePeriodUntil = SystemClock.elapsedRealtime() + AUTO_GRACE_PERIOD_MS
                                _alwaysRecordingState.value = EspAlwaysRecordingState.GRACE_PERIOD
                            } else {
                                val remainingSeconds = ((AUTO_RECORD_DURATION_MS - elapsed + 999L) / 1000L)
                                    .coerceAtLeast(0L)
                                _message.value = "자동 녹음 중 · ${remainingSeconds}초 후 저장"
                            }
                        }

                        EspAlwaysRecordingState.GRACE_PERIOD -> {
                            if (now >= gracePeriodUntil) {
                                _alwaysRecordingState.value = EspAlwaysRecordingState.MONITORING
                                _message.value = "3초 유예가 끝났습니다. 다시 음량을 감지합니다."
                            } else {
                                val remainingSeconds = ((gracePeriodUntil - now + 999L) / 1000L)
                                    .coerceAtLeast(0L)
                                _message.value = "재감지 유예 중 · ${remainingSeconds}초 남음"
                            }
                        }

                        EspAlwaysRecordingState.SAVING -> Unit
                        EspAlwaysRecordingState.ERROR -> return@launch
                        EspAlwaysRecordingState.OFF -> {
                            _alwaysRecordingState.value = EspAlwaysRecordingState.MONITORING
                        }
                    }

                    delay(200)
                }
            } catch (error: Exception) {
                if (isActive && _alwaysRecordingEnabled.value) {
                    _alwaysRecordingState.value = EspAlwaysRecordingState.ERROR
                    _message.value = error.message ?: "상시 녹음 처리에 실패했습니다."
                }
            } finally {
                if (!_alwaysRecordingEnabled.value) {
                    _alwaysRecordingState.value = EspAlwaysRecordingState.OFF
                }
            }
        }
    }

    fun stopAlwaysRecording() {
        _alwaysRecordingEnabled.value = false
        if (alwaysMonitoringJob?.isActive != true) {
            _alwaysRecordingState.value = EspAlwaysRecordingState.OFF
            _message.value = "상시 녹음을 중지했습니다."
        } else {
            _message.value = "상시 녹음 종료 중... 현재 녹음은 안전하게 저장합니다."
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive && _connectionState.value == EspRecorderConnectionState.RECORDING) {
                delay(1000)
                try {
                    val previousStatus = _status.value
                    val currentStatus = client.getStatus(_baseUrl.value)
                    applyStatus(currentStatus)
                    if (currentStatus.recording) {
                        _message.value = "ESP32 녹음 중 · ${currentStatus.durationMs / 1000}초"
                    } else if (previousStatus?.recording == true &&
                        (currentStatus.hasRecording || currentStatus.bytesWritten > 44L)
                    ) {
                        // The firmware stops automatically at its 30-second safety limit.
                        // Import that file immediately so a new session cannot overwrite it.
                        _isBusy.value = true
                        try {
                            saveLatestRecording(currentStatus, note = "자동 종료")
                            _message.value = "30초 제한으로 ESP32 녹음이 종료되어 WAV를 자동 저장했습니다."
                        } catch (saveError: Exception) {
                            _message.value = saveError.message ?: "자동 종료된 ESP32 녹음을 저장하지 못했습니다."
                        } finally {
                            _isBusy.value = false
                        }
                    }
                } catch (error: Exception) {
                    // Keep the recording state visible. The device has its own 30-second
                    // safety limit, so a temporary status timeout does not stop the device.
                    _message.value = "상태 확인 재시도 중: ${error.message ?: "네트워크 오류"}"
                }
            }
        }
    }

    private suspend fun saveLatestRecording(status: EspRecorderStatus, note: String) {
        val destination = File(
            getApplication<Application>().filesDir,
            "esp32_rec_${System.currentTimeMillis()}.wav"
        )
        val recordedMaxDb = maxOf(status.maxLevelDb, status.levelDb)
        val recordedAverageDb = if (status.averageLevelDb > 0.0) {
            status.averageLevelDb
        } else {
            recordedMaxDb
        }

        try {
            client.downloadLatest(_baseUrl.value, destination)
            val classification = withContext(Dispatchers.Default) {
                runCatching { audioClassifier?.classify(destination) }
                    .onFailure { error ->
                        Log.w("EspRecorderViewModel", "오디오 분류에 실패했습니다.", error)
                    }
                    .getOrNull()
            }
            val sourceNote = if (note.isBlank()) {
                "ESP32 외장 마이크"
            } else {
                "ESP32 외장 마이크 · ${note.trim()}"
            }
            val recordNote = classificationNote(sourceNote, classification)

            repository.insert(
                NoiseRecord(
                    timestamp = System.currentTimeMillis(),
                    // These values use the ESP32's calibrated-relative level.
                    // A physical dB SPL calibration is still required for legal measurements.
                    maxDb = recordedMaxDb,
                    avgDb = recordedAverageDb,
                    maxVibration = 0.0,
                    isExceeded = false,
                    isNoiseExceeded = false,
                    isVibeExceeded = false,
                    recordingPath = destination.absolutePath,
                    durationMs = status.durationMs,
                    note = recordNote,
                    recordingSource = "ESP32",
                    classificationLabel = classification?.label ?: "unclassified",
                    classificationConfidence = classification?.confidence?.toDouble() ?: 0.0,
                    isInterfloorCandidate = classification?.isInterfloorCandidate ?: false
                )
            )
        } catch (error: Exception) {
            if (destination.exists()) destination.delete()
            throw error
        }
    }

    private fun classificationNote(
        sourceNote: String,
        classification: AudioClassificationResult?
    ): String {
        if (classification == null) return sourceNote
        val confidence = String.format(
            Locale.getDefault(),
            "%.0f",
            classification.confidence * 100.0f
        )
        return "$sourceNote · AI 분류: ${classification.label} ($confidence%)"
    }

    private fun applyStatus(newStatus: EspRecorderStatus) {
        _status.value = newStatus
        _currentLevelDb.value = newStatus.levelDb
        _connectionState.value = if (newStatus.recording) {
            EspRecorderConnectionState.RECORDING
        } else if (newStatus.wifiConnected) {
            EspRecorderConnectionState.CONNECTED
        } else {
            EspRecorderConnectionState.DISCONNECTED
        }
    }

    private fun connectedMessage(status: EspRecorderStatus): String {
        val address = status.ip.ifBlank { status.hostname.ifBlank { _baseUrl.value } }
        return "ESP32 연결됨 · $address"
    }

    override fun onCleared() {
        _alwaysRecordingEnabled.value = false
        pollingJob?.cancel()
        operationJob?.cancel()
        alwaysMonitoringJob?.cancel()
        audioClassifier?.close()
        super.onCleared()
    }
}
