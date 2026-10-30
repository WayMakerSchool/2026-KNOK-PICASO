package com.example.ui.v3

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.NoiseRecord
import com.example.BuildConfig
import com.example.ui.SoundAnalysisPanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateText(value:Long,pattern:String)=SimpleDateFormat(pattern,Locale.KOREA).format(Date(value))
private fun durationText(ms:Long):String { val seconds=ms.coerceAtLeast(0)/1000;return "%02d:%02d".format(seconds/60,seconds%60) }

@Composable
fun V3RecordsScreen(records: List<NoiseRecord>, playingId: Int?, playing: Boolean,
    onOpen:(NoiseRecord)->Unit,onPlay:(NoiseRecord)->Unit,onReport:()->Unit,onPrepare:()->Unit={}) {
    val groups=records.sortedByDescending { it.timestamp }.groupBy { dateText(it.timestamp,"yyyy.MM.dd") }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=18.dp,bottom=20.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                Text("저장된 녹음",fontSize=30.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold,color=V3Blue)
                Text("원음을 듣고, AI 추정 결과를 확인하세요.",fontSize=14.sp,color=V3Blue,modifier=Modifier.padding(top=7.dp),textAlign=TextAlign.Center)
                OutlinedButton(onClick=onReport,enabled=records.isNotEmpty(),shape=CircleShape,
                    modifier=Modifier.fillMaxWidth().padding(top=22.dp,bottom=4.dp).heightIn(min=50.dp)) {
                    Icon(Icons.Outlined.Description,null,Modifier.size(23.dp));Spacer(Modifier.width(10.dp))
                    Text("상담 참고보고서 만들기",fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                }
            }
        }
        if(records.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical=55.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AudioFile,null,Modifier.size(48.dp),tint=V3Blue)
                Text("아직 저장된 녹음이 없어요",color=V3Blue,fontSize=17.sp,modifier=Modifier.padding(top=16.dp))
                Text("KNOK에서 받은 새 녹음이 여기에 표시돼요.",color=V3Blue,fontSize=13.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=9.dp))
                Text("이전 앱의 기록은 자동으로 옮겨지지 않아요.",color=V3Blue,fontSize=12.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=6.dp))
                OutlinedButton(onClick=onPrepare,shape=CircleShape,modifier=Modifier.padding(top=18.dp).heightIn(min=48.dp)) {
                    Text("감지 준비하기")
                }
            }
        }
        groups.forEach { (date,dayRecords) ->
            item(key="date-$date") {
                Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f),color=V3Line)
                    Text(date,color=V3Blue,fontSize=13.sp,modifier=Modifier.padding(horizontal=14.dp))
                    HorizontalDivider(Modifier.weight(1f),color=V3Line)
                }
            }
            items(dayRecords,key={it.id}) { record ->
                V3RecordingCard(record,playingId==record.id && playing,{onOpen(record)},{onPlay(record)})
            }
        }
        item { V3InfoNote("소리 종류의 추정이며,\n발생 위치·원인을 확정하지 않습니다.") }
    }
}

@Composable
fun V3RecordingCard(record:NoiseRecord,playing:Boolean,onOpen:()->Unit,onPlay:()->Unit) {
    val presentation=v3RecordPresentation(record)
    Surface(color=V3Surface,shape=RoundedCornerShape(26.dp),shadowElevation=1.dp,
        modifier=Modifier.fillMaxWidth().clickable(onClick=onOpen)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                FilledTonalIconButton(onClick=onPlay,modifier=Modifier.size(49.dp),
                    colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=V3Pale,contentColor=V3Blue)) {
                    Icon(if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if(playing)"녹음 일시정지" else "녹음 재생",Modifier.size(30.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(presentation.title,fontSize=21.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,color=V3Blue)
                    Text(presentation.badge,fontSize=13.sp,color=V3Blue,modifier=Modifier.padding(top=4.dp))
                    Text(dateText(record.timestamp,"yyyy.MM.dd HH:mm:ss"),fontSize=12.sp,color=V3Blue,modifier=Modifier.padding(top=6.dp))
                }
                Icon(Icons.Outlined.GraphicEq,null,Modifier.padding(start=6.dp).size(24.dp),tint=V3Blue)
            }
            HorizontalDivider(color=V3Line,modifier=Modifier.padding(vertical=14.dp))
            Text("최고  ${v3RelativeLevel(record.maxDb,true)} dB · 상대값",fontSize=21.sp,fontWeight=FontWeight.SemiBold,color=V3Blue)
            presentation.alternative?.let {
                Text("다른 가능성: $it",fontSize=12.sp,lineHeight=18.sp,color=V3Blue,modifier=Modifier.padding(top=8.dp))
            }
        }
    }
}

@Composable
fun V3RecordDetail(record:NoiseRecord,playing:Boolean,progress:Float,analyzing:Boolean,error:String?,
    onBack:()->Unit,onPlay:()->Unit,onAnalyze:()->Unit,onNote:()->Unit,onDelete:()->Unit,analysisSummary:String?=null) {
    val presentation=v3RecordPresentation(record)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=10.dp),
        verticalArrangement=Arrangement.spacedBy(17.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Outlined.ArrowBack,"녹음목록으로 돌아가기") }
            Text("녹음 상세",fontSize=22.sp,fontWeight=FontWeight.Bold,color=V3Blue,modifier=Modifier.weight(1f))
            IconButton(onClick=onNote) { Icon(Icons.Outlined.EditNote,"녹음 메모 편집") }
            IconButton(onClick=onDelete) { Icon(Icons.Outlined.DeleteOutline,"녹음 삭제") }
        }
        Text(dateText(record.timestamp,"yyyy.MM.dd · HH:mm:ss"),fontSize=13.sp,color=V3Blue)
        Surface(shape=RoundedCornerShape(26.dp),color=V3Pale) {
            Column(Modifier.fillMaxWidth().padding(21.dp)) {
                Text("추정 소리",fontSize=13.sp,color=V3Blue)
                Text(presentation.title,fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,color=V3Blue,modifier=Modifier.padding(vertical=10.dp))
                Text(presentation.badge,fontSize=13.sp,color=V3Blue)
            }
        }
        Surface(shape=RoundedCornerShape(26.dp),color=V3Surface) {
            Column(Modifier.padding(21.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("원본 녹음",fontSize=17.sp,color=V3Blue,modifier=Modifier.align(Alignment.Start))
                Icon(Icons.Outlined.GraphicEq,"녹음 아이콘",Modifier.padding(vertical=20.dp).size(62.dp),tint=V3Blue)
                LinearProgressIndicator(progress=progress.coerceIn(0f,1f),modifier=Modifier.fillMaxWidth(),color=V3Blue,trackColor=V3Line)
                Row(Modifier.fillMaxWidth().padding(top=9.dp,bottom=17.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(durationText((record.durationMs*progress).toLong()),fontSize=12.sp,color=V3Blue)
                    Text(durationText(record.durationMs),fontSize=12.sp,color=V3Blue)
                }
                Button(onClick=onPlay,shape=CircleShape,modifier=Modifier.heightIn(min=48.dp)) {
                    Icon(if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null)
                    Text(if(playing)"일시정지" else "원본 재생",modifier=Modifier.padding(horizontal=10.dp))
                }
            }
        }
        val macPending=BuildConfig.KNOK_CANDIDATE && record.deviceClassificationPending && record.recordingSource=="ESP32" &&
            record.deviceEventId!=null && record.soundAnalysisJson==null
        if(macPending) {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                V3InfoNote("AI 분석은 맥 연결에 따라 대기할 수 있어요.\n대기 중인 녹음을 다시 확인할 수 있어요.")
                if(!analysisSummary.isNullOrBlank()) Text("최근 분석 상태: $analysisSummary",fontSize=12.sp,lineHeight=18.sp,color=V3Blue)
                OutlinedButton(onClick=onAnalyze,shape=CircleShape,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text("대기 중인 분석 다시 시도")
                }
            }
        } else SoundAnalysisPanel(record=record,isAnalyzing=analyzing,canAnalyze=true,error=error,onAnalyze=onAnalyze)
        Text("길이 ${record.durationMs/1000}초 · 최고 ${v3RelativeLevel(record.maxDb,true)} dB · 상대값",fontSize=13.sp,color=V3Blue)
        if(record.note.isNotBlank())Text("메모\n${record.note}",fontSize=13.sp,lineHeight=20.sp,color=V3Blue)
        V3InfoNote("원본 기록과 AI 추정은 별도로 보관돼요.\n층 방향은 검증 범위가 제한된 연구 결과예요.")
        Spacer(Modifier.height(6.dp))
    }
}
