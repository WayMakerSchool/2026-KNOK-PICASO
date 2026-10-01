package com.example.ui

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NoiseRecord
import com.example.data.NoiseRepository
import com.example.data.FirebaseSyncManager
import com.example.auth.AccountSession
import com.example.ml.GeminiSoundAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class NoiseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: NoiseRepository
    val noiseThreshold = 57.0
    val vibeThreshold = 0.05 // Sensible threshold for floor vibration (m/s2)

    init {
        val database = AppDatabase.getDatabase(application)
        val syncManager = FirebaseSyncManager.getInstance(application)
        repository = NoiseRepository(database.noiseDao(), syncManager)
    }

    // Database flows
    val allRecords: StateFlow<List<NoiseRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val exceededRecords: StateFlow<List<NoiseRecord>> = repository.exceededRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Measurement State
    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val _currentDb = MutableStateFlow(30.0)
    val currentDb: StateFlow<Double> = _currentDb.asStateFlow()

    private val _currentVibe = MutableStateFlow(0.0)
    val currentVibe: StateFlow<Double> = _currentVibe.asStateFlow()

    private val _maxSessionDb = MutableStateFlow(30.0)
    val maxSessionDb: StateFlow<Double> = _maxSessionDb.asStateFlow()

    private val _maxSessionVibe = MutableStateFlow(0.0)
    val maxSessionVibe: StateFlow<Double> = _maxSessionVibe.asStateFlow()

    private val _waveHistory = MutableStateFlow<List<Float>>(emptyList())
    val waveHistory: StateFlow<List<Float>> = _waveHistory.asStateFlow()

    private val _sessionDurationS = MutableStateFlow(0L)
    val sessionDurationS: StateFlow<Long> = _sessionDurationS.asStateFlow()

    // Alert indicator
    private val _isLimitExceededNow = MutableStateFlow(false)
    val isLimitExceededNow: StateFlow<Boolean> = _isLimitExceededNow.asStateFlow()

    // Playback State
    private val _playingRecordId = MutableStateFlow<Int?>(null)
    val playingRecordId: StateFlow<Int?> = _playingRecordId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _analyzingRecordId = MutableStateFlow<Int?>(null)
    val analyzingRecordId = _analyzingRecordId.asStateFlow()
    private val _analysisErrors = MutableStateFlow<Map<Int, String>>(emptyMap())
    val analysisErrors = _analysisErrors.asStateFlow()
    private val soundAnalyzer = GeminiSoundAnalyzer(application)
    private var analysisJob: Job? = null

    fun analyzeSound(record: NoiseRecord) {
        if (_analyzingRecordId.value != null || record.soundAnalysisJson != null) return
        val owner = AccountSession.userId.value
        if (record.userId != owner) return
        _analyzingRecordId.value = record.id
        _analysisErrors.value = _analysisErrors.value - record.id
        analysisJob = viewModelScope.launch {
            try {
                val current = repository.findById(record.id) ?: return@launch
                if (current.userId != owner || current.soundAnalysisJson != null) return@launch
                val localPath = current.recordingPath?.takeIf { File(it).isFile }
                    ?: withContext(Dispatchers.IO) {
                        FirebaseSyncManager.getInstance(getApplication()).downloadAudio(current)
                    }
                    ?: error("녹음 파일을 찾을 수 없습니다. 동기화 상태를 확인해 주세요.")
                val result = soundAnalyzer.analyze(File(localPath))
                if (AccountSession.userId.value == owner) {
                    repository.saveSoundAnalysis(current.id, owner, result.toJson())
                }
            } catch (error: TimeoutCancellationException) {
                _analysisErrors.value = _analysisErrors.value + (record.id to "분석 시간이 초과되었습니다. 다시 시도해 주세요.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("NoiseViewModel", "소리 분석에 실패했습니다.", error)
                val message = if (error is IllegalArgumentException || error is IllegalStateException) {
                    error.message ?: "소리를 분석하지 못했습니다. 다시 시도해 주세요."
                } else {
                    "분석에 실패했습니다. 인터넷 연결과 소리 분석 서버·앱 인증 설정을 확인한 뒤 다시 시도해 주세요."
                }
                _analysisErrors.value = _analysisErrors.value + (record.id to message)
            } finally {
                _analyzingRecordId.value = null
                analysisJob = null
            }
        }
    }

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    // Internal Recorders & Sensors
    private var mediaRecorder: MediaRecorder? = null
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var activeAudioFile: File? = null

    private var dbSum = 0.0
    private var dbCount = 0

    private var measurementJob: Job? = null
    private var timerJob: Job? = null
    private var playerPollJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    // Vibration EMA Filter variables
    private var smoothedGravity = 9.80665
    private val alpha = 0.92 // Sensitivity modifier for gravity baseline smoothing

    // Sensor Listener
    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val rawMagnitude = sqrt((x * x + y * y + z * z).toDouble())
            
            // Apply exponential moving average to filter gravity out
            smoothedGravity = alpha * smoothedGravity + (1.0 - alpha) * rawMagnitude
            val dynamicVibe = abs(rawMagnitude - smoothedGravity)

            // Dynamic Vibe represents vibration amplitude spike
            _currentVibe.value = dynamicVibe
            _maxSessionVibe.value = max(_maxSessionVibe.value, dynamicVibe)

            // Check live vibration threshold limit
            updateLiveThresholdCheck()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun updateLiveThresholdCheck() {
        val currentDbVal = _currentDb.value
        val currentVibeVal = _currentVibe.value
        _isLimitExceededNow.value = currentDbVal >= noiseThreshold || currentVibeVal >= vibeThreshold
    }

    fun startMeasurement(context: Context) {
        if (_isMeasuring.value) return

        // Setup File for recording
        val timeStamp = System.currentTimeMillis()
        val filename = "quiet_rec_$timeStamp.m4a"
        activeAudioFile = File(context.filesDir, filename)

        // Setup Media Recorder
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(activeAudioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("NoiseViewModel", "Failed to start MediaRecorder: ${e.message}")
            // Fallback: If microphone crashes, we proceed with only vibration
            mediaRecorder = null
        }

        // Setup Sensor Manager for accelerometer
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(sensorEventListener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)

        // Reset variables
        _isMeasuring.value = true
        _currentDb.value = 30.0
        _currentVibe.value = 0.0
        _maxSessionDb.value = 30.0
        _maxSessionVibe.value = 0.0
        _waveHistory.value = emptyList()
        _sessionDurationS.value = 0L
        smoothedGravity = 9.80665
        dbSum = 0.0
        dbCount = 0

        // Start measurement and polling job
        measurementJob = viewModelScope.launch {
            val history = mutableListOf<Float>()
            while (_isMeasuring.value) {
                delay(100) // Poll amplitude every 100ms for responsiveness

                var amp = 0
                try {
                    amp = mediaRecorder?.maxAmplitude ?: 0
                } catch (e: Exception) {
                    // Ignore transient exceptions if recorder is closed
                }

                // Convert max amplitude (0-32767) to decibels
                // Standard offset 15 ensures target range ~30dB (ambient) to ~95dB is mapped
                val dbVal = if (amp > 0) {
                    20.0 * kotlin.math.log10(amp.toDouble()) + 15.0
                } else {
                    30.0 + (Math.random() * 3.0) // Small organic flutter when silent
                }

                val clampedDb = dbVal.coerceIn(30.0, 95.0)
                _currentDb.value = clampedDb
                _maxSessionDb.value = max(_maxSessionDb.value, clampedDb)

                // Accumulate statistics
                dbSum += clampedDb
                dbCount++

                // Update Waveform sequence (keep last 50 entries)
                history.add(clampedDb.toFloat())
                if (history.size > 50) history.removeAt(0)
                _waveHistory.value = history.toList()

                updateLiveThresholdCheck()
            }
        }

        // Timer job
        timerJob = viewModelScope.launch {
            while (_isMeasuring.value) {
                delay(1000)
                _sessionDurationS.value += 1
            }
        }
    }

    fun stopMeasurementAndSave(note: String = "") {
        if (!_isMeasuring.value) return

        // Stop measurement flags and loops
        _isMeasuring.value = false
        measurementJob?.cancel()
        timerJob?.cancel()

        // Unregister Sensor listener
        sensorManager?.unregisterListener(sensorEventListener)

        // Stop & Release Media Recorder safely
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("NoiseViewModel", "Error stopping MediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        // Write record to database
        val duration = _sessionDurationS.value * 1000L // convert to ms
        val maxDb = _maxSessionDb.value
        val avgDb = if (dbCount > 0) dbSum / dbCount else maxDb
        val maxVibe = _maxSessionVibe.value

        val isNoiseViolation = maxDb >= noiseThreshold
        val isVibeViolation = maxVibe >= vibeThreshold
        val isExceeded = isNoiseViolation || isVibeViolation

        val recordingPath = activeAudioFile?.absolutePath

        viewModelScope.launch(Dispatchers.IO) {
            val finalRecord = NoiseRecord(
                timestamp = System.currentTimeMillis(),
                maxDb = maxDb,
                avgDb = avgDb,
                maxVibration = maxVibe,
                isExceeded = isExceeded,
                isNoiseExceeded = isNoiseViolation,
                isVibeExceeded = isVibeViolation,
                recordingPath = recordingPath,
                durationMs = duration,
                note = note
            )
            repository.insert(finalRecord)
        }
    }

    fun stopMeasurementWithoutSaving() {
        if (!_isMeasuring.value) return
        _isMeasuring.value = false
        measurementJob?.cancel()
        timerJob?.cancel()
        sensorManager?.unregisterListener(sensorEventListener)

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("NoiseViewModel", "Error stopping recorder: ${e.message}")
        }
        mediaRecorder = null

        // Delete active audio recording file
        activeAudioFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        activeAudioFile = null
    }

    // Audio Playback Engine
    fun playAudio(record: NoiseRecord) {
        val path = record.recordingPath?.takeIf { File(it).exists() }
        if (path == null) {
            if (record.remoteAudioPath == null) {
                Log.e("NoiseViewModel", "Media file does not exist")
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    FirebaseSyncManager.getInstance(getApplication()).downloadAudio(record)
                }.onSuccess { downloadedPath ->
                    if (downloadedPath != null) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            playAudio(record.copy(recordingPath = downloadedPath))
                        }
                    }
                }.onFailure { error ->
                    Log.e("NoiseViewModel", "원격 녹음 파일을 내려받지 못했습니다.", error)
                }
            }
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Log.e("NoiseViewModel", "Media file does not exist")
            return
        }

        // If clicking already playing file, toggle pause
        if (_playingRecordId.value == record.id) {
            togglePlayPause()
            return
        }

        // Release old player before setting up new one
        releasePlayer()

        _playingRecordId.value = record.id
        _isPlaying.value = true
        _playbackProgress.value = 0f

        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(path)
                prepare()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _playbackProgress.value = 1f
                    _playingRecordId.value = null
                    playerPollJob?.cancel()
                }
                start()
            } catch (e: IOException) {
                Log.e("NoiseViewModel", "Failed to play audio: ${e.message}")
                _isPlaying.value = false
                _playingRecordId.value = null
                return
            }
        }

        // Poll playing progress
        playerPollJob?.cancel()
        playerPollJob = viewModelScope.launch {
            while (activityIsPlayingAudio()) {
                val current = mediaPlayer?.currentPosition ?: 0
                val total = mediaPlayer?.duration ?: 1
                _playbackProgress.value = current.toFloat() / total.toFloat()
                delay(100)
            }
        }
    }

    private fun activityIsPlayingAudio(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
            } else {
                player.start()
                _isPlaying.value = true
            }
        } catch (e: Exception) {
            Log.e("NoiseViewModel", "Error toggling player state: ${e.message}")
        }
    }

    fun stopAudio() {
        releasePlayer()
        _isPlaying.value = false
        _playingRecordId.value = null
        _playbackProgress.value = 0f
    }

    private fun releasePlayer() {
        playerPollJob?.cancel()
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            // Ignore state exceptions during teardown
        }
        mediaPlayer = null
    }

    fun updateRecordNote(record: NoiseRecord, newNote: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(record.copy(note = newNote))
        }
    }

    fun deleteRecord(record: NoiseRecord) {
        if (_analyzingRecordId.value == record.id) analysisJob?.cancel()
        _analysisErrors.value = _analysisErrors.value - record.id
        if (_playingRecordId.value == record.id) {
            stopAudio()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(record)
            record.recordingPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    fun clearAllData() {
        analysisJob?.cancel()
        _analysisErrors.value = emptyMap()
        stopAudio()
        if (_isMeasuring.value) {
            stopMeasurementWithoutSaving()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.clear()
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
        if (_isMeasuring.value) {
            sensorManager?.unregisterListener(sensorEventListener)
            try {
                mediaRecorder?.release()
            } catch (e: Exception) {}
        }
    }
}
