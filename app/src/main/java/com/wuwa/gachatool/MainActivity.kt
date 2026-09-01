package com.wuwa.gachatool

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf

private val Ink = Color(0xFF101214); private val Panel = Color(0xFF191C1E); private val Gold = Color(0xFFE8B84A); private val Text = Color(0xFFE9E4D9); private val Muted = Color(0xFF96958F)

class MainActivity : ComponentActivity() {
    private lateinit var db: GachaDatabase
    private val activeUid = mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = GachaDatabase.create(this); lifecycleScope.launch { activeUid.value = db.dao().uids().firstOrNull().orEmpty() }; lifecycleScope.launch { ResourcePack.refresh(this@MainActivity) }; setContent { WuwaTheme { MobileHome(db, activeUid.value, onCloud = { startActivityForResult(Intent(this, CloudGachaActivity::class.java), 42) }, onImport = { importUrl(it) }) } } }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == 42 && resultCode == RESULT_OK) data?.getStringExtra("url")?.let { importUrl(it) } }
    private fun importUrl(url: String) { lifecycleScope.launch {
        runCatching {
            val fetched = GachaService.importFromUrl(url)
            val merged = db.dao().mergeRecords(fetched.records)
            fetched to merged
        }.onSuccess { (fetched, merged) ->
            activeUid.value = fetched.uid
            val failed = if (fetched.failedPools.isEmpty()) "" else "，${fetched.failedPools.size} 个卡池失败"
            Toast.makeText(this@MainActivity, "导入完成：新增 ${merged.addedCount} 条，重复 ${merged.duplicateCount} 条$failed", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this@MainActivity, it.message ?: "导入失败", Toast.LENGTH_LONG).show()
        }
    } }
}

@Composable private fun WuwaTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Gold, onBackground = Text, onSurface = Text), content = content) }

@Composable private fun MobileHome(db: GachaDatabase, currentUid: String, onCloud: () -> Unit, onImport: (String) -> Unit) {
    var uid by remember(currentUid) { mutableStateOf(currentUid) }; var pool by remember { mutableStateOf("1") }; var url by remember { mutableStateOf("") }; var showImport by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    val availableUids by db.dao().observeUids().collectAsState(initial = emptyList())
    LaunchedEffect(currentUid, availableUids) {
        if (currentUid.isNotBlank()) uid = currentUid
        else if (uid !in availableUids) uid = availableUids.firstOrNull().orEmpty()
    }
    val recordsFlow = remember(uid) { if (uid.isBlank()) flowOf(emptyList()) else db.dao().records(uid) }
    val records by recordsFlow.collectAsState(initial = emptyList())
    val visibleFiveStars = records.filter { it.pool == pool && it.quality == 5 }; val five = records.count { it.quality == 5 }; val pulls = records.size; val visiblePools = (1..13).map(Int::toString).filter { id -> records.any { it.pool == id } || id in listOf("1", "2", "3", "4") }
    Scaffold(containerColor = Ink, floatingActionButton = { FloatingActionButton(onClick = { showImport = true }, containerColor = Gold, contentColor = Ink, shape = CircleShape, modifier = Modifier.size(56.dp)) { ImportIcon() } }) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 88.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("唤取记录", color = Text, fontSize = 23.sp, fontWeight = FontWeight.Bold); if (uid.isBlank()) Text("尚未导入数据", color = Muted, fontSize = 13.sp) else UidSelector(uid, availableUids) { uid = it } }; Text("${records.size} 条记录", color = Muted, fontSize = 12.sp) } }
            item { Overview(records, pulls, five) }
            item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Panel, RoundedCornerShape(14.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) { visiblePools.forEach { id -> Text(ImportParser.poolName(id), modifier = Modifier.width(116.dp).clickable { pool = id }.background(if (pool == id) Gold.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(vertical = 11.dp), color = if (pool == id) Gold else Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } } }
            val pity = currentPity(records, pool)
            if (pity > 0) item { CurrentPityRow(pity) }
            if (visibleFiveStars.isEmpty()) item { Text("暂无${ImportParser.poolName(pool)}五星记录", modifier = Modifier.fillMaxWidth().padding(top = 52.dp), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } else items(visibleFiveStars) { RecordRow(it, pityForFiveStar(it, records)) }
        }
    }
    if (showImport) Dialog(onDismissRequest = { showImport = false }) { Surface(shape = RoundedCornerShape(8.dp), color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .32f))) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("导入唤取记录", color = Text, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("选择一种数据来源", color = Muted, fontSize = 12.sp) }; TextButton(onClick = { showImport = false }, contentPadding = PaddingValues(8.dp)) { Text("关闭", color = Muted) } }; Button(onClick = { onCloud(); showImport = false }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("从云鸣潮自动提取", fontWeight = FontWeight.Bold) }; HorizontalDivider(color = Color.White.copy(alpha = .08f)); OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("官方唤取记录链接") }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(6.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = .16f))); if (message.isNotBlank()) Text(message, color = Color(0xFFD99A9A), fontSize = 12.sp); Button(onClick = { if (url.isNotBlank()) { onImport(url); showImport = false } else message = "请先粘贴链接" }, enabled = true, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .09f), contentColor = Text)) { Text("导入链接") } } } }
}

@Composable private fun UidSelector(uid: String, uids: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable { expanded = true }.padding(top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("UID $uid", color = Muted, fontSize = 13.sp)
            Canvas(Modifier.size(8.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, size.height * .3f); lineTo(size.width, size.height * .3f); lineTo(size.width / 2, size.height * .8f); close() }
                drawPath(path, Gold.copy(alpha = .78f))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 176.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = Color(0xFF242522),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            uids.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text("UID $candidate", color = if (candidate == uid) Gold else Text, fontSize = 13.sp, fontWeight = if (candidate == uid) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { onSelect(candidate); expanded = false },
                    trailingIcon = { if (candidate == uid) Text("当前", color = Gold.copy(alpha = .72f), fontSize = 10.sp) },
                    colors = MenuDefaults.itemColors(textColor = Text),
                )
            }
        }
    }
}

private fun currentPity(records: List<GachaRecord>, pool: String): Int = records.filter { it.pool == pool }.takeWhile { it.quality != 5 }.size
@Composable private fun CurrentPityRow(pity: Int) { Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Spacer(Modifier.width(34.dp)); QuestionAvatar(Modifier.size(44.dp)); Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) { Row(verticalAlignment = Alignment.Bottom) { Text(pity.toString(), color = Gold, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold); Text(" 抽", color = Color(0xFFC4C8CC), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp)); Text("当前垫抽", color = Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 1.dp)) }; PityBar(pity) }; Spacer(Modifier.width(30.dp)) } }
private fun pityForFiveStar(target: GachaRecord, records: List<GachaRecord>): Int {
    var found = false
    var count = 0
    // API `count` is a page-local field (often 1), so derive the pity interval
    // from the complete, pool-isolated history instead of displaying it directly.
    for (record in records.filter { it.pool == target.pool }) {
        if (!found) {
            if (record.id == target.id) { found = true; count = 1 }
        } else {
            if (record.quality == 5) break
            count += 1
        }
    }
    return count.coerceAtLeast(1)
}
@Composable private fun ImportIcon() { androidx.compose.foundation.Canvas(Modifier.size(24.dp)) { val stroke=2.2.dp.toPx(); drawLine(Ink, start=androidx.compose.ui.geometry.Offset(size.width/2, 2.dp.toPx()), end=androidx.compose.ui.geometry.Offset(size.width/2, 15.dp.toPx()), strokeWidth=stroke); drawLine(Ink, start=androidx.compose.ui.geometry.Offset(size.width/2, 15.dp.toPx()), end=androidx.compose.ui.geometry.Offset(7.dp.toPx(), 10.dp.toPx()), strokeWidth=stroke); drawLine(Ink, start=androidx.compose.ui.geometry.Offset(size.width/2, 15.dp.toPx()), end=androidx.compose.ui.geometry.Offset(17.dp.toPx(), 10.dp.toPx()), strokeWidth=stroke); drawArc(Ink, 0f, 180f, false, topLeft=androidx.compose.ui.geometry.Offset(3.dp.toPx(), 13.dp.toPx()), size=androidx.compose.ui.geometry.Size(18.dp.toPx(), 9.dp.toPx()), style=Stroke(stroke)) } }
private val OverviewLabelShape = GenericShape { size, _ -> moveTo(0f, 0f); lineTo(size.width * .9f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .9f, size.height); lineTo(0f, size.height); lineTo(size.width * .05f, size.height / 2); close() }
@Composable private fun Overview(records: List<GachaRecord>, pulls: Int, five: Int) { val role = records.filter { it.pool == "1" }; val weapon = records.filter { it.pool == "2" }; Box(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(top = 19.dp).border(1.dp, Color(0xFF3F403C), RoundedCornerShape(8.dp)).background(Color(0xFF242522), RoundedCornerShape(8.dp)).padding(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 10.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { PoolStat("角色活动唤取", role.size, role.count { it.quality == 5 }, role.count { it.offRate }, true, Modifier.weight(3f)); PoolStat("武器活动唤取", weapon.size, weapon.count { it.quality == 5 }, weapon.count { it.offRate }, false, Modifier.weight(2f)) } }; Row(Modifier.padding(start = 10.dp).height(38.dp).clip(OverviewLabelShape).background(Color(0xFFF0C34D)).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("数据概览", color = Color(0xFF171817), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold); Text("$pulls 抽 · $five 金", color = Color(0xFF5E4B1D), fontSize = 12.sp, fontWeight = FontWeight.Bold) } } }
@Composable private fun OverviewMetric(label: String, value: String, modifier: Modifier) { Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(4.dp)); Text(value, color = Text, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center) } }
@Composable private fun PoolStat(label: String, pulls: Int, five: Int, offRate: Int, role: Boolean, modifier: Modifier) { Column(modifier.height(126.dp).border(1.dp, Color(0xFF49483F), RoundedCornerShape(7.dp)).background(Color(0xFF201F1A), RoundedCornerShape(7.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) { Text(label, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) { Text(pulls.toString(), color = Text, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold); Text("抽", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { PoolMetric("出金", five.toString(), Modifier.weight(1f)); PoolMetricDivider(); PoolMetric("平均", if (five == 0) "--" else "%.1f".format(pulls.toFloat() / five), Modifier.weight(1f)); if (role) { PoolMetricDivider(); PoolMetric("平均UP", if (five == offRate) "--" else "%.1f".format(pulls.toFloat() / (five - offRate)), Modifier.weight(1f)) } } } }
@Composable private fun PoolMetric(label: String, value: String, modifier: Modifier) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Color(0xFF888A83), fontSize = 10.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(2.dp)); Text(value, color = Color(0xFFD5D4CC), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) } }
@Composable private fun PoolMetricDivider() { Box(Modifier.width(1.dp).height(25.dp).background(Color(0xFF464740))) }
@Composable private fun QuestionAvatar(modifier: Modifier = Modifier) { Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2F34)), contentAlignment = Alignment.Center) { Text("?", color = Color(0xFFC8CBC5), fontSize = 22.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun RecordRow(record: GachaRecord, pityCount: Int) { Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(formatRecordDate(record.time), color = Muted, fontSize = 10.sp, modifier = Modifier.width(34.dp)); ResourceAvatar(record, Modifier.size(44.dp)); Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) { Row(verticalAlignment = Alignment.Bottom) { Text(pityCount.toString(), color = Gold, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold); Text(" 抽", color = Color(0xFFC4C8CC), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp)); Text(record.name.ifBlank { "未知资源" }, color = Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 1.dp)) }; PityBar(pityCount) }; OffRateStamp(record.offRate) } }
private fun formatRecordDate(value: String): String = if (value.length >= 10) value.substring(5, 10) else value
@Composable private fun PityBar(pity: Int) { val progress=(pity.coerceIn(0,80)/80f); val base=when { pity >= 66 -> Color(0xFFE98A31); pity >= 40 -> Color(0xFFF3BD38); else -> Color(0xFF9ED02D) }; Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 3.dp)) { drawRoundRect(Color(0xFF44494E), cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())); val filled=size.width*progress; clipRect(right=filled) { drawRoundRect(base, size=androidx.compose.ui.geometry.Size(filled,size.height), cornerRadius=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())); var x=-size.height; while(x<filled+size.height){ drawLine(Color.White.copy(alpha=.22f), androidx.compose.ui.geometry.Offset(x,size.height), androidx.compose.ui.geometry.Offset(x+size.height*1.8f,0f), strokeWidth=2.2.dp.toPx()); x+=14.dp.toPx() } } } }
@Composable private fun OffRateStamp(active: Boolean) { Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { if (active) { Canvas(Modifier.size(28.dp).rotate(-7f)) { drawOval(Color(0x1CD84848)); drawOval(Color(0xD1D84848), style=Stroke(1.5.dp.toPx())); drawOval(Color(0xB8EC8F84), topLeft=androidx.compose.ui.geometry.Offset(3.dp.toPx(),3.dp.toPx()), size=androidx.compose.ui.geometry.Size(size.width-6.dp.toPx(),size.height-6.dp.toPx()), style=Stroke(1.dp.toPx())) }; Text("歪", color=Color(0xFFF2B0A5), fontSize=10.sp, fontWeight=FontWeight.ExtraBold, modifier=Modifier.rotate(-7f)) } } }
