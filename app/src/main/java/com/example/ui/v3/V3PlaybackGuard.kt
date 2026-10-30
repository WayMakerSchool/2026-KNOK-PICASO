package com.example.ui.v3

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.example.data.NoiseRecord

/** Do not start listening to the app's own playback without the user's choice. */
@Composable
fun V3DetectionStartGuard(
    playing: Boolean,
    onStart: () -> Unit,
    onStopAndStart: () -> Unit,
    content: @Composable (() -> Unit) -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    content { if(playing) confirming=true else onStart() }
    if(confirming) AlertDialog(onDismissRequest={confirming=false},containerColor=V3Cream,
        title={Text("녹음이 재생 중이에요")},
        text={Text("재생 소리가 KNOK에 감지되지 않도록 먼저 재생을 멈추고 감지를 켤 수 있어요.")},
        confirmButton={TextButton(onClick={confirming=false;onStopAndStart()}){Text("재생 멈추고 감지")}},
        dismissButton={TextButton(onClick={confirming=false}){Text("취소")}})
}

/** Pausing is immediate; starting playback while the device listens requires an explicit choice. */
@Composable
fun V3PlaybackGuard(
    listening: Boolean,
    records: List<NoiseRecord>,
    playingId: Int?,
    playing: Boolean,
    onPlay: (NoiseRecord) -> Unit,
    content: @Composable ((NoiseRecord) -> Unit) -> Unit
) {
    var pendingId by remember { mutableStateOf<Int?>(null) }
    content { record ->
        val isPausing=playing && playingId==record.id
        if(listening && !isPausing) pendingId=record.id else onPlay(record)
    }
    pendingId?.let { id ->
        val pending=records.firstOrNull { it.id==id }
        AlertDialog(onDismissRequest={pendingId=null},containerColor=V3Cream,
            title={Text("기기의 감지 상태를 확인해 주세요")},
            text={Text("재생 소리가 KNOK에 다시 녹음될 수 있어요. 필요하면 감지를 중지한 뒤 재생해 주세요.")},
            confirmButton={TextButton(enabled=pending!=null,onClick={
                pendingId=null
                if(pending!=null) onPlay(pending)
            }) { Text("계속 재생") }},
            dismissButton={TextButton(onClick={pendingId=null}) { Text("취소") }})
    }
}
