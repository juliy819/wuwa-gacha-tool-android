package com.wuwa.gachatool

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

private val Ink = Color(0xFF101214); private val Panel = Color(0xFF191C1E); private val Gold = Color(0xFFE8B84A); private val Text = Color(0xFFE9E4D9); private val Muted = Color(0xFFB7B5AE)
private val Success = Color(0xFF8FC8BE); private val Warning = Color(0xFFE7BD78); private val Error = Color(0xFFE5A5A5)

class MainActivity : ComponentActivity() {
    private lateinit var db: GachaDatabase
    private lateinit var oneDrive: OneDriveSyncService
    private val activeUid = mutableStateOf("")
    private val syncDisplay = mutableStateOf("尚未同步")
    private val dataRevision = mutableStateOf(0)
    private val availableUpdate = mutableStateOf<AndroidUpdate?>(null)
    private val backProgress = mutableStateOf(0f)
    private val backEdge = mutableStateOf(0)
    private val backTouchY = mutableStateOf(0f)
    private val settingsOpen = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if (Build.VERSION.SDK_INT >= 34) { onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, object : android.window.OnBackAnimationCallback { override fun onBackStarted(backEvent: android.window.BackEvent) { backEdge.value = backEvent.swipeEdge; backTouchY.value = backEvent.touchY; backProgress.value = 0f }; override fun onBackProgressed(backEvent: android.window.BackEvent) { if (settingsOpen.value) backProgress.value = backEvent.progress.coerceIn(0f, 1f) }; override fun onBackCancelled() { backProgress.value = 0f }; override fun onBackInvoked() { backProgress.value = 0f; if (settingsOpen.value) settingsOpen.value = false else finish() } }) }; db = GachaDatabase.create(this); oneDrive = OneDriveSyncService(this, SyncRepository(this, db)); lifecycleScope.launch { val localUids = db.dao().uids(); activeUid.value = localUids.firstOrNull().orEmpty(); runBackgroundSync(localUids) }; lifecycleScope.launch { ResourcePack.refresh(this@MainActivity) }; lifecycleScope.launch { availableUpdate.value = AndroidUpdateService.check() }; setContent { WuwaTheme { MobileHome(db, oneDrive, activeUid.value, syncDisplay, dataRevision, availableUpdate, backProgress, backEdge, backTouchY, settingsOpen, onUidChanged = { activeUid.value = it }, onCloud = { startActivityForResult(Intent(this, CloudGachaActivity::class.java), 42) }, onImport = { importUrl(it) }) } } }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == 42 && resultCode == RESULT_OK) data?.getStringExtra("url")?.let { importUrl(it) } }
    override fun onStart() { super.onStart(); enableEdgeToEdge(); if (::oneDrive.isInitialized) scheduleBackgroundSync() }
    private fun scheduleBackgroundSync() { lifecycleScope.launch { kotlinx.coroutines.delay(350); runBackgroundSync(db.dao().uids()) } }
    private suspend fun runBackgroundSync(localUids: List<String>) { if (!oneDrive.status().connected) { syncDisplay.value = "未连接 OneDrive"; return }; syncDisplay.value = "正在检查云端…"; runCatching { oneDrive.syncAll(localUids) }.onSuccess { result -> activeUid.value = db.dao().uids().firstOrNull().orEmpty(); dataRevision.value += 1; syncDisplay.value = if (result.addedCount > 0) "已更新 · 共 ${result.totalCount} 条" else "已是最新 · 共 ${result.totalCount} 条" }.onFailure { syncDisplay.value = "检查失败 · 打开同步查看详情" } }
    private fun importUrl(url: String) { lifecycleScope.launch {
        runCatching {
            val fetched = GachaService.importFromUrl(url)
            val merged = db.dao().mergeRecords(fetched.records)
            fetched to merged
        }.onSuccess { (fetched, merged) ->
            activeUid.value = fetched.uid
            dataRevision.value += 1
            scheduleBackgroundSync()
            val failed = if (fetched.failedPools.isEmpty()) "" else "，${fetched.failedPools.size} 个卡池失败"
            Toast.makeText(this@MainActivity, "导入完成：新增 ${merged.addedCount} 条，重复 ${merged.duplicateCount} 条$failed", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this@MainActivity, it.message ?: "导入失败", Toast.LENGTH_LONG).show()
        }
    } }
}

@Composable private fun WuwaTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel, primary = Gold, onBackground = Text, onSurface = Text), content = content) }

@Composable private fun MobileHome(db: GachaDatabase, oneDrive: OneDriveSyncService, currentUid: String, syncDisplay: MutableState<String>, dataRevision: MutableState<Int>, availableUpdate: MutableState<AndroidUpdate?>, backProgress: MutableState<Float>, backEdge: MutableState<Int>, backTouchY: MutableState<Float>, settingsOpen: MutableState<Boolean>, onUidChanged: (String) -> Unit, onCloud: () -> Unit, onImport: (String) -> Unit) {
    var uid by remember(currentUid) { mutableStateOf(currentUid) }; var pool by remember { mutableStateOf("1") }; var url by remember { mutableStateOf("") }; var showImport by remember { mutableStateOf(false) }; var showSync by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var syncStatus by remember { mutableStateOf(oneDrive.status()) }; var deviceLogin by remember { mutableStateOf<DeviceLoginInfo?>(null) }; var syncBusy by remember { mutableStateOf(false) }; var syncMessage by remember { mutableStateOf("") }; var syncConflict by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val context = LocalContext.current; val revision = dataRevision.value
    val clipboard = LocalClipboardManager.current
    val availableUids by remember(revision) { db.dao().observeUids() }.collectAsState(initial = emptyList())
    val uidRecordCounts by remember(revision) { db.dao().observeUidRecordCounts() }.collectAsState(initial = emptyList())
    LaunchedEffect(currentUid, availableUids) {
        if (currentUid.isNotBlank()) uid = currentUid
        else if (uid !in availableUids) uid = availableUids.firstOrNull().orEmpty()
    }
    val recordsFlow = remember(uid, revision) { if (uid.isBlank()) flowOf(emptyList()) else db.dao().records(uid) }
    val records by recordsFlow.collectAsState(initial = emptyList())
    LaunchedEffect(deviceLogin) {
        val login = deviceLogin ?: return@LaunchedEffect
        while (deviceLogin != null && Instant.now().isBefore(login.expiresAt)) {
            delay(login.intervalSeconds * 1000)
            runCatching { oneDrive.pollLogin() }.onSuccess {
                if (it == LoginPollResult.CONNECTED) { syncStatus = oneDrive.status(); deviceLogin = null; syncMessage = "OneDrive 已连接" }
            }.onFailure { deviceLogin = null; syncStatus = oneDrive.status(); syncMessage = it.message ?: "登录失败" }
        }
    }
    val visibleFiveStars = records.filter { it.pool == pool && it.quality == 5 }; val five = records.count { it.quality == 5 }; val pulls = records.size; val visiblePools = (1..13).map(Int::toString).filter { id -> records.any { it.pool == id } || id in listOf("1", "2", "3", "4") }
    var showSettings by settingsOpen
    BackHandler(enabled = showSettings && Build.VERSION.SDK_INT < 34) { showSettings = false }
    Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = Ink) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp).navigationBarsPadding().widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("唤取记录", color = Text, fontSize = 23.sp, fontWeight = FontWeight.Bold); if (uid.isBlank()) Text("尚未导入数据", color = Muted, fontSize = 13.sp) else UidSelector(uid, availableUids) { uid = it }; SyncStatusLine(syncDisplay.value) }; Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape).clickable { showSettings = true }.background(Color.White.copy(alpha = .06f)).semantics { contentDescription = "打开设置" }, contentAlignment = Alignment.Center) { SettingsIcon() } } }
            availableUpdate.value?.let { update -> item { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(7.dp)).background(Gold.copy(alpha = .13f)).clickable { AndroidUpdateService.openDownload(context, update) }.padding(horizontal = 12.dp, vertical = 10.dp).semantics { contentDescription = "发现 Android 新版本 ${update.version}，下载更新" }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("发现 Android 新版本 v${update.version}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("下载更新", color = Gold, fontSize = 11.sp) } } }
            item { Overview(records, pulls, five) }
            item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Panel, RoundedCornerShape(14.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) { visiblePools.forEach { id -> Text(ImportParser.poolName(id), modifier = Modifier.width(116.dp).heightIn(min = 48.dp).clickable { pool = id }.background(if (pool == id) Gold.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(vertical = 11.dp).semantics { contentDescription = ImportParser.poolName(id); stateDescription = if (pool == id) "已选中" else "未选中" }, color = if (pool == id) Gold else Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } } }
            val pity = currentPity(records, pool)
            item { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .07f))) { Column(Modifier.padding(vertical = 4.dp)) { if (pity > 0) CurrentPityRow(pity); if (visibleFiveStars.isEmpty()) Text("暂无${ImportParser.poolName(pool)}五星记录", modifier = Modifier.fillMaxWidth().padding(vertical = 46.dp), color = Muted, textAlign = TextAlign.Center) else visibleFiveStars.forEachIndexed { index, record -> RecordRow(record, pityForFiveStar(record, records)); if (index < visibleFiveStars.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .055f), modifier = Modifier.padding(horizontal = 10.dp)) } } } }
        }
        }
        if (showSettings) SettingsScreen(
            syncDisplay = syncDisplay.value,
            syncStatus = syncStatus,
            update = availableUpdate.value,
            backProgress = backProgress,
            backEdge = backEdge,
            backTouchY = backTouchY,
            onBack = { showSettings = false },
            onImport = { showImport = true },
            onSync = { syncStatus = oneDrive.status(); showSync = true },
            onUpdate = { update -> AndroidUpdateService.openDownload(context, update) },
        )
    }
    if (showImport) Dialog(onDismissRequest = { showImport = false }) { Surface(shape = RoundedCornerShape(8.dp), color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .32f))) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("导入唤取记录", color = Text, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("选择一种数据来源", color = Muted, fontSize = 12.sp) }; TextButton(onClick = { showImport = false }, contentPadding = PaddingValues(8.dp)) { Text("关闭", color = Muted) } }; Button(onClick = { onCloud(); showImport = false }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { Text("从云鸣潮自动提取", fontWeight = FontWeight.Bold) }; HorizontalDivider(color = Color.White.copy(alpha = .08f)); OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("官方唤取记录链接") }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(6.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = .16f))); if (message.isNotBlank()) Text(message, color = Color(0xFFD99A9A), fontSize = 12.sp); Button(onClick = { if (url.isNotBlank()) { onImport(url); showImport = false } else message = "请先粘贴链接" }, enabled = true, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .09f), contentColor = Text)) { Text("导入链接") } } } }
    if (showSync) Dialog(onDismissRequest = { if (!syncBusy) { oneDrive.cancelLogin(); deviceLogin = null; syncStatus = oneDrive.status(); showSync = false } }) { Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF202326), border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .3f))) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("OneDrive 云同步", color = Text, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("双向合并全部 UID 的抽卡记录", color = Muted, fontSize = 12.sp) }; TextButton(onClick = { showSync = false }, enabled = !syncBusy) { Text("关闭", color = Muted) } }
        Text("同步整个抽卡数据库，包含删除和清空结果；并发修改会先停止并要求选择。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        if (!syncStatus.configured) Text("当前构建未配置 OneDrive Client ID，云同步暂不可用。", color = Color(0xFFD9BD9A), fontSize = 12.sp)
        else if (!syncStatus.connected) {
            if (deviceLogin == null) Button(onClick = { syncBusy = true; syncMessage = ""; scope.launch { runCatching { oneDrive.startLogin() }.onSuccess { deviceLogin = it; syncStatus = oneDrive.status(); clipboard.setText(AnnotatedString(it.userCode)); syncMessage = "设备码已自动复制"; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.verificationUri))) }.onFailure { syncMessage = it.message ?: "登录失败" }; syncBusy = false } }, enabled = !syncBusy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { if (syncBusy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Ink) else Text("连接 OneDrive", fontWeight = FontWeight.Bold) }
            else Column(Modifier.fillMaxWidth().border(1.dp, Gold.copy(alpha = .22f), RoundedCornerShape(6.dp)).background(Gold.copy(alpha = .06f), RoundedCornerShape(6.dp)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("在浏览器中输入验证码", color = Muted, fontSize = 11.sp); Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(deviceLogin!!.userCode, color = Gold, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 8.dp)); TextButton(onClick = { clipboard.setText(AnnotatedString(deviceLogin!!.userCode)); syncMessage = "设备码已复制" }) { Text("复制", color = Gold, fontSize = 12.sp) } }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = Gold); Text("等待 Microsoft 登录确认", color = Muted, fontSize = 11.sp) }; TextButton(onClick = { oneDrive.cancelLogin(); deviceLogin = null; syncStatus = oneDrive.status(); syncMessage = "已取消登录等待" }) { Text("取消等待", color = Color(0xFFD99A9A), fontSize = 12.sp) } }; TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deviceLogin!!.verificationUri))) }) { Text("重新打开登录页", color = Gold, fontSize = 12.sp) } }
        } else {
            if (uidRecordCounts.isEmpty()) Text("尚无本地 UID，可从 OneDrive 导入", color = Muted, fontSize = 12.sp)
            else Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(6.dp))) {
                uidRecordCounts.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("UID ${item.uid}", color = Text, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("${item.recordCount} 条记录", color = Muted, fontSize = 11.sp) }; Box(Modifier.size(8.dp).background(Color(0xFF7AB88A), CircleShape)) }
                    if (index < uidRecordCounts.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .06f))
                }
            }
            Button(onClick = { syncBusy = true; syncMessage = ""; syncDisplay.value = "正在同步…"; scope.launch { runCatching { oneDrive.syncAll(availableUids) }.onSuccess { result -> val selected = uid.takeIf { it in result.uids } ?: result.uids.firstOrNull().orEmpty(); uid = selected; onUidChanged(selected); dataRevision.value += 1; syncMessage = "已同步 ${result.uids.size} 个 UID，新增 ${result.addedCount} 条记录" + if (result.failures.isNotEmpty()) "；失败：${result.failures.joinToString("；")}" else ""; syncDisplay.value = if (result.addedCount > 0) "已更新 · 共 ${result.totalCount} 条" else "已是最新 · 共 ${result.totalCount} 条" }.onFailure { val text = syncErrorMessage(it); syncConflict = text.contains("首次连接") || text.contains("都已发生变化"); syncMessage = if (syncConflict) "本机和云端都有修改，请选择保留哪一份" else text; syncDisplay.value = "同步失败 · 请查看详情" }; syncStatus = oneDrive.status(); syncBusy = false } }, enabled = !syncBusy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink)) { if (syncBusy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Ink) else Text(if (uid.isBlank()) "从 OneDrive 导入" else "同步全部账号", fontWeight = FontWeight.Bold) }
            if (syncConflict) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { syncConflict = false; syncBusy = true; scope.launch { runCatching { oneDrive.syncAll(availableUids, "local") }.onSuccess { syncMessage = "已使用本机数据覆盖云端" }.onFailure { syncMessage = syncErrorMessage(it) }; syncBusy = false } }, modifier = Modifier.weight(1f)) { Text("保留本机", fontSize = 12.sp) }
                OutlinedButton(onClick = { syncConflict = false; syncBusy = true; scope.launch { runCatching { oneDrive.syncAll(availableUids, "remote") }.onSuccess { dataRevision.value += 1; syncMessage = "已使用云端数据覆盖本机" }.onFailure { syncMessage = syncErrorMessage(it) }; syncBusy = false } }, modifier = Modifier.weight(1f)) { Text("使用云端", fontSize = 12.sp) }
            }
            TextButton(onClick = { oneDrive.disconnect(); syncStatus = oneDrive.status(); syncMessage = "已断开 OneDrive" }, enabled = !syncBusy, modifier = Modifier.align(Alignment.End)) { Text("断开连接", color = Muted, fontSize = 12.sp) }
        }
        if (syncMessage.isNotBlank()) Text(syncMessage, color = if (syncMessage.contains("失败") || syncMessage.contains("错误") || syncMessage.contains("失效")) Color(0xFFD99A9A) else Color(0xFF8FC8BE), fontSize = 12.sp, lineHeight = 18.sp)
    } } }
}

@Composable private fun SettingsIcon() { Canvas(Modifier.size(19.dp)) { val stroke = 1.6.dp.toPx(); val ys = floatArrayOf(4.dp.toPx(), 9.5.dp.toPx(), 15.dp.toPx()); val knobs = floatArrayOf(12.dp.toPx(), 6.dp.toPx(), 14.dp.toPx()); ys.forEachIndexed { index, y -> drawLine(Muted, androidx.compose.ui.geometry.Offset(2.dp.toPx(), y), androidx.compose.ui.geometry.Offset(17.dp.toPx(), y), strokeWidth = stroke); drawCircle(Ink, radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(knobs[index], y)); drawCircle(Muted, radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(knobs[index], y), style = Stroke(stroke)) } } }
@Composable private fun CloudSyncIcon(connected: Boolean) { Canvas(Modifier.size(19.dp)) { val color = if (connected) Gold else Muted; val stroke = 1.6.dp.toPx(); drawArc(color, 190f, 215f, false, topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 5.dp.toPx()), size = androidx.compose.ui.geometry.Size(15.dp.toPx(), 10.dp.toPx()), style = Stroke(stroke)); drawLine(color, androidx.compose.ui.geometry.Offset(6.dp.toPx(), 15.dp.toPx()), androidx.compose.ui.geometry.Offset(13.dp.toPx(), 15.dp.toPx()), strokeWidth = stroke); drawLine(color, androidx.compose.ui.geometry.Offset(9.5.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.Offset(9.5.dp.toPx(), 13.dp.toPx()), strokeWidth = stroke); drawLine(color, androidx.compose.ui.geometry.Offset(9.5.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.Offset(7.dp.toPx(), 10.5.dp.toPx()), strokeWidth = stroke); drawLine(color, androidx.compose.ui.geometry.Offset(9.5.dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.Offset(12.dp.toPx(), 10.5.dp.toPx()), strokeWidth = stroke) } }

@Composable private fun SettingsScreen(syncDisplay: String, syncStatus: OneDriveStatus, update: AndroidUpdate?, backProgress: State<Float>, backEdge: State<Int>, backTouchY: State<Float>, onBack: () -> Unit, onImport: () -> Unit, onSync: () -> Unit, onUpdate: (AndroidUpdate) -> Unit) {
    val context = LocalContext.current
    fun openRepository(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(Modifier.fillMaxSize().widthIn(max = 600.dp).graphicsLayer { val p = backProgress.value; val edge = backEdge.value; val scale = 1f - p * .1f; val anchorLeft = edge == android.window.BackEvent.EDGE_RIGHT; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (anchorLeft) 0f else 1f, 0f); translationX = if (anchorLeft) 0f else size.width * (1f - scale); val safeTouchY = backTouchY.value.coerceIn(0f, size.height); translationY = (safeTouchY * (1f - scale)).coerceIn(0f, size.height * (1f - scale)); scaleX = scale; scaleY = scale }.background(Ink).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) { Text("‹  返回", color = Gold, fontSize = 14.sp) }; Text("设置", color = Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp)) }
        Text("数据", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        SettingsActionCard("数据导入", "云鸣潮自动提取或粘贴官方唤取链接", "打开导入入口", onImport)
        SettingsActionCard("OneDrive 云同步", if (syncStatus.connected) syncDisplay else "未连接 OneDrive", if (syncStatus.connected) "管理同步" else "连接并同步", onSync)
        Text("应用", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        SettingsActionCard("版本更新", update?.let { "发现新版本 v${it.version}" } ?: "当前已是最新版本", update?.let { "下载 v${it.version}" } ?: "暂无更新", update?.let { { onUpdate(it) } } ?: {})
        Text("关于", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        SettingsActionCard("主仓库", "wuwa-gacha-tool", "打开 GitHub", { openRepository("https://github.com/juliy819/wuwa-gacha-tool") })
        SettingsActionCard("Android 项目", "wuwa-gacha-tool-android", "打开 GitHub", { openRepository("https://github.com/juliy819/wuwa-gacha-tool-android") })
        Text("Wuwa Gacha Tool Android", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
}

@Composable private fun SettingsActionCard(title: String, detail: String, action: String, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = action != "暂无更新", onClick = onClick).semantics { contentDescription = "$title，$detail，$action" }, shape = RoundedCornerShape(10.dp), color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .08f))) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = Text, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text(detail, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }; Text(action, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold) } } }

@Composable private fun SyncStatusLine(status: String) { val color = when { status.contains("失败") -> Error; status.contains("正在") -> Warning; status.contains("更新") || status.contains("最新") -> Success; else -> Muted }; Row(Modifier.heightIn(min = 24.dp).semantics { contentDescription = "同步状态：$status" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Box(Modifier.size(6.dp).background(color, CircleShape)); Text(status, color = color, fontSize = 11.sp) } }

@Composable private fun UidSelector(uid: String, uids: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable { expanded = true }.padding(top = 2.dp, bottom = 2.dp).semantics { contentDescription = "选择 UID，当前为 $uid"; stateDescription = "有 ${uids.size} 个账号" },
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
@Composable private fun CurrentPityRow(pity: Int) { Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp).semantics { contentDescription = "当前垫抽 $pity 抽" }, verticalAlignment = Alignment.CenterVertically) { QuestionAvatar(Modifier.size(38.dp)); Box(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp).height(24.dp), contentAlignment = Alignment.CenterStart) { PityBar(pity, Modifier.fillMaxSize()); Text("$pity 抽", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 9.dp, bottom = 1.dp)) }; Spacer(Modifier.size(28.dp)) } }
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
@Composable private fun PoolStat(label: String, pulls: Int, five: Int, offRate: Int, role: Boolean, modifier: Modifier) { Column(modifier.heightIn(min = 132.dp).border(1.dp, Color(0xFF49483F), RoundedCornerShape(7.dp)).background(Color(0xFF201F1A), RoundedCornerShape(7.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(label, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) { Text(pulls.toString(), color = Text, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold); Text("抽", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { PoolMetric("出金", five.toString(), Modifier.weight(1f)); PoolMetricDivider(); PoolMetric("平均", if (five == 0) "--" else "%.1f".format(pulls.toFloat() / five), Modifier.weight(1f)); if (role) { PoolMetricDivider(); PoolMetric("平均UP", if (five == offRate) "--" else "%.1f".format(pulls.toFloat() / (five - offRate)), Modifier.weight(1f)) } } } }
@Composable private fun PoolMetric(label: String, value: String, modifier: Modifier) { Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Color(0xFF888A83), fontSize = 10.sp, textAlign = TextAlign.Center); Spacer(Modifier.height(2.dp)); Text(value, color = Color(0xFFD5D4CC), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) } }
@Composable private fun PoolMetricDivider() { Box(Modifier.width(1.dp).height(25.dp).background(Color(0xFF464740))) }
private fun syncErrorMessage(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: "同步失败（${error.javaClass.simpleName}）"
@Composable private fun QuestionAvatar(modifier: Modifier = Modifier) { Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2F34)).semantics { contentDescription = "垫抽记录" }, contentAlignment = Alignment.Center) { Text("?", color = Color(0xFFC8CBC5), fontSize = 22.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun RecordRow(record: GachaRecord, pityCount: Int) { Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp).semantics { contentDescription = "五星记录，垫抽 $pityCount 抽${if (record.offRate) "，歪" else "，命中 UP"}" }, verticalAlignment = Alignment.CenterVertically) { ResourceAvatar(record, Modifier.size(38.dp)); Box(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp).height(24.dp), contentAlignment = Alignment.CenterStart) { PityBar(pityCount, Modifier.fillMaxSize()); Text("$pityCount 抽", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 9.dp, bottom = 1.dp)) }; OffRateStamp(record.offRate) } }
private fun formatRecordDate(value: String): String = if (value.length >= 10) value.substring(5, 10) else value
@Composable private fun PityBar(pity: Int, modifier: Modifier = Modifier) { val progress=(pity.coerceIn(0,80)/80f); val base=when { pity >= 66 -> Color(0xFFF04F3F); pity >= 40 -> Color(0xFFF2BE42); else -> Color(0xFF22B947) }; Canvas(modifier.semantics { contentDescription = "垫抽进度 $pity 抽，共 80 抽保底" }.padding(top = 1.dp)) { val radius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()); drawRoundRect(Color(0xFF41443F), cornerRadius=radius); val filled=size.width*progress; clipRect(right=filled) { drawRoundRect(base, size=androidx.compose.ui.geometry.Size(filled,size.height), cornerRadius=radius); var x=-size.height; while(x<filled+size.height){ drawLine(Color.White.copy(alpha=.11f), androidx.compose.ui.geometry.Offset(x,size.height), androidx.compose.ui.geometry.Offset(x+size.height*.34f,0f), strokeWidth=10.dp.toPx()); x+=18.dp.toPx() } } } }
@Composable private fun OffRateStamp(active: Boolean) { Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { if (active) { Canvas(Modifier.size(28.dp).rotate(-7f)) { drawOval(Color(0x1CD84848)); drawOval(Color(0xD1D84848), style=Stroke(1.5.dp.toPx())); drawOval(Color(0xB8EC8F84), topLeft=androidx.compose.ui.geometry.Offset(3.dp.toPx(),3.dp.toPx()), size=androidx.compose.ui.geometry.Size(size.width-6.dp.toPx(),size.height-6.dp.toPx()), style=Stroke(1.dp.toPx())) }; Text("歪", color=Color(0xFFF2B0A5), fontSize=10.sp, fontWeight=FontWeight.ExtraBold, modifier=Modifier.rotate(-7f)) } } }
