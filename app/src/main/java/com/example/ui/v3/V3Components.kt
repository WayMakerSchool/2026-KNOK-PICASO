package com.example.ui.v3

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val V3Cream = Color(0xFFF7F2EB)
val V3Blue = Color(0xFF006FBA)
val V3Surface = Color(0xFFFFFCF8)
val V3Pale = Color(0xFFEAF3F9)
val V3Line = Color(0xFFD6E2E9)

@Composable
fun KnokDeviceIcon(modifier: Modifier = Modifier, color: Color = V3Blue) {
    Canvas(modifier.size(28.dp)) {
        val w = size.width; val h = size.height; val line = 1.5.dp.toPx()
        drawOval(color, Offset(w*.08f,h*.16f), Size(w*.84f,h*.38f), style=Stroke(line))
        drawArc(color, 0f, 180f, false, Offset(w*.08f,h*.42f), Size(w*.84f,h*.38f), style=Stroke(line))
        drawLine(color, Offset(w*.08f,h*.35f), Offset(w*.08f,h*.61f), line)
        drawLine(color, Offset(w*.92f,h*.35f), Offset(w*.92f,h*.61f), line)
        drawCircle(color, w*.025f, Offset(w*.5f,h*.35f))
    }
}

@Composable
fun V3Header(onDevices: () -> Unit, onProfile: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=18.dp)) {
        OutlinedIconButton(onClick=onDevices, shape=RoundedCornerShape(12.dp),
            border=androidx.compose.foundation.BorderStroke(1.dp,V3Line),
            modifier=Modifier.align(Alignment.CenterStart).width(64.dp).height(46.dp).testTag("v3_device_picker")) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                KnokDeviceIcon(Modifier.size(28.dp)); Icon(Icons.Outlined.ExpandMore,"현재 기기 정보",Modifier.size(18.dp))
            }
        }
        Text("KNOK", color=V3Blue, fontSize=25.sp, fontWeight=FontWeight.ExtraBold,
            letterSpacing=(-1.3).sp, modifier=Modifier.align(Alignment.Center))
        OutlinedIconButton(onClick=onProfile, shape=RoundedCornerShape(12.dp),
            border=androidx.compose.foundation.BorderStroke(1.dp,V3Line),
            modifier=Modifier.align(Alignment.CenterEnd).size(46.dp)) {
            Icon(Icons.Outlined.PersonOutline,"프로필과 더보기",Modifier.size(29.dp))
        }
    }
}

@Composable
fun V3BottomNavigation(route: String, onNavigate: (String) -> Unit) {
    val selectedRoute = when(route) { "detail" -> "records"; "device_advanced" -> "device"; else -> route }
    val tabs = listOf(Triple("home", "홈", Icons.Outlined.Home),
        Triple("records", "기록", Icons.Outlined.Description),
        Triple("report", "리포트", Icons.Outlined.BarChart),
        Triple("device", "기기", Icons.Outlined.Sensors))
    val selectedIndex = tabs.indexOfFirst { it.first == selectedRoute }
    Surface(color = V3Cream) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 7.dp)) {
            Surface(shape = RoundedCornerShape(30.dp), color = V3Surface,
                border = BorderStroke(1.dp, V3Line), shadowElevation = 2.dp) {
                BoxWithConstraints(Modifier.fillMaxWidth().height(68.dp)) {
                    val tabWidth = maxWidth / tabs.size
                    val indicatorX = animateDpAsState(
                        targetValue = tabWidth * selectedIndex.coerceAtLeast(0),
                        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                        label = "selected tab position"
                    )
                    if (selectedIndex >= 0) {
                        Box(Modifier.offset(x = indicatorX.value).width(tabWidth).fillMaxHeight()
                            .padding(horizontal = 4.dp, vertical = 5.dp)
                            .clip(RoundedCornerShape(26.dp)).background(V3Pale)
                            .testTag("v3_nav_indicator"))
                    }
                    Row(Modifier.fillMaxSize()) {
                        tabs.forEach { (id, label, image) ->
                            Column(Modifier.weight(1f).fillMaxHeight()
                                .testTag("v3_tab_$id")
                                .semantics { selected = selectedRoute == id }
                                .clip(RoundedCornerShape(30.dp))
                                .clickable(role = Role.Tab) { onNavigate(id) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                if (id == "device") KnokDeviceIcon(Modifier.size(25.dp))
                                else Icon(image, null, Modifier.size(25.dp), tint = V3Blue)
                                Spacer(Modifier.height(3.dp))
                                Text(label, color = V3Blue, fontSize = 12.sp,
                                    fontWeight = if (selectedRoute == id) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun V3Action(text: String, onClick: () -> Unit, enabled: Boolean=true, stop: Boolean=false) {
    Button(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(40.dp),
        colors=ButtonDefaults.buttonColors(containerColor=V3Blue,contentColor=V3Cream),
        contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp),
        modifier=Modifier.fillMaxWidth().heightIn(min=58.dp).testTag("v3_primary_action")) {
        Surface(shape=CircleShape,color=V3Cream,modifier=Modifier.size(38.dp)) {
            Box(contentAlignment=Alignment.Center) { Icon(if(stop)Icons.Outlined.Stop else Icons.Outlined.PowerSettingsNew,null,Modifier.size(25.dp),tint=V3Blue) }
        }
        Text(text,fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).padding(horizontal=9.dp))
        if(!stop) Icon(Icons.Outlined.ChevronRight,null,Modifier.size(25.dp)) else Spacer(Modifier.width(14.dp))
    }
}

@Composable
fun V3InfoNote(text: String, modifier: Modifier=Modifier) {
    Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=V3Pale) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq,null,Modifier.size(29.dp),tint=V3Blue)
            Spacer(Modifier.width(14.dp))
            Text(text,fontSize=13.sp,lineHeight=20.sp,color=V3Blue)
        }
    }
}

@Composable
fun V3SectionTitle(title: String, action: String?=null, onAction: () -> Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(title,color=V3Blue,fontSize=21.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        if(action!=null) TextButton(onClick=onAction) { Text(action,fontSize=13.sp); Icon(Icons.Outlined.ChevronRight,null,Modifier.size(19.dp)) }
    }
}
