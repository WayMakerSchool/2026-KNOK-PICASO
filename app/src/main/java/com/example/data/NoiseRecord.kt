package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "noise_records", indices = [Index(value = ["deviceEventId"], unique = true)])
data class NoiseRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val maxDb: Double,
    val avgDb: Double,
    val maxVibration: Double,
    val isExceeded: Boolean, // true if maxDb >= 57.0 or maxVibration >= vibeThreshold
    val isNoiseExceeded: Boolean, // true if maxDb >= 57.0
    val isVibeExceeded: Boolean, // true if maxVibration >= vibeThreshold
    val recordingPath: String?,
    val durationMs: Long,
    val note: String = "",
    val recordingSource: String = "PHONE",
    val classificationLabel: String = "unclassified",
    val classificationConfidence: Double = 0.0,
    val isInterfloorCandidate: Boolean = false,
    val userId: String? = null,
    val syncId: String? = null,
    val remoteAudioPath: String? = null,
    val syncState: String = "LOCAL",
    val soundAnalysisJson: String? = null,
    val deviceEventId: String? = null,
    val deviceMetadataJson: String? = null,
    @ColumnInfo(defaultValue = "0") val deviceRawEdges: Long = 0,
    @ColumnInfo(defaultValue = "0") val deviceDigitalEvents: Long = 0,
    val deviceStartSample: Long? = null,
    val deviceTriggerSample: Long? = null,
    val deviceSampleCount: Long? = null,
    @ColumnInfo(defaultValue = "0") val deviceClassificationPending: Boolean = false
)
