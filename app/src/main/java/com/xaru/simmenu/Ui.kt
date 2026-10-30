package com.xaru.simmenu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.math.abs
import kotlin.math.max

val Yellow = Color(0xFFFFCC00)
val YellowDark = Color(0xFFE6B800)
val Bg = Color(0xFF0F0F12)
val CardC = Color(0xFF1C1C22)
val Card2 = Color(0xFF26262E)
val Green = Color(0xFF4CD964)
val Red = Color(0xFFFF6B6B)

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ---------- общие элементы ----------
@Composable
fun StripeLogo(diameter: Dp) {
    Canvas(Modifier.size(diameter)) {
        val p = Path().apply { addOval(Rect(0f, 0f, size.width, size.height)) }
        drawCircle(Color.Black)
        clipPath(p) {
            val h = size.height / 7f
            for (i in 0..2) drawRect(Yellow, Offset(0f, h * (1 + 2 * i)), Size(size.width, h))
        }
    }
}

@Composable
fun YellowHeader(
    title: String, subtitle: String, modifier: Modifier = Modifier,
    right: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Yellow, YellowDark)), RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .statusBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StripeLogo(46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = Color.Black.copy(alpha = .65f), fontSize = 13.sp)
            }
            right()
        }
        content()
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color.Black.copy(alpha = .12f), RoundedCornerShape(14.dp)).padding(10.dp)) {
        Text(label, color = Color.Black.copy(alpha = .6f), fontSize = 11.sp)
        Text(value, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
fun SmallDialogButton(text: String, onClick: () -> Unit, enabled: Boolean = true, accent: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = if (accent) Yellow else Color.LightGray, fontWeight = if (accent) FontWeight.Bold else FontWeight.Normal)
    }
}

// ---------- SIM-меню ----------
private data class SimItem(val icon: ImageVector, val title: String, val sub: String, val answer: String, val opensData: Boolean = false)

@Composable
fun SimScreen(
    hasKey: Boolean,
    onUnlock: suspend (String) -> Boolean,
    onSetup: suspend (String) -> Unit,
    onRestore: () -> Unit,
    onUnlocked: () -> Unit,
    onOpenData: () -> Unit,
    tapsRequired: Int
) {
    var taps by remember { mutableIntStateOf(0) }
    var last by remember { mutableLongStateOf(0L) }
    var askPassword by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<SimItem?>(null) }

    val menu = remember {
        listOf(
            SimItem(Icons.Default.AccountCircle, "Баланс", "Узнать остаток на счёте", "Ваш баланс будет отправлен в SMS."),
            SimItem(Icons.Default.Search, "Интернет", "Пакеты и трафик", "Услуга временно недоступна."),
            SimItem(Icons.Default.Share, "Данные", "Мобильные данные", "", opensData = true),
            SimItem(Icons.Default.Star, "Тарифы", "Сменить тарифный план", "Список тарифов загружается…"),
            SimItem(Icons.Default.Settings, "Услуги", "Подключить и отключить", "Услуга временно недоступна."),
            SimItem(Icons.Default.Email, "SMS и MMS", "Пакеты сообщений", "Пакеты сообщений недоступны."),
            SimItem(Icons.Default.Favorite, "Бонусы", "Баллы и подарки", "У вас пока нет бонусов."),
            SimItem(Icons.Default.Phone, "Мой номер", "Информация о SIM", "Номер скрыт оператором."),
            SimItem(Icons.Default.Info, "Помощь", "Служба поддержки", "Служба поддержки: 0611")
        )
    }

    Column(Modifier.fillMaxSize()) {
        YellowHeader(
            title = "билайн", subtitle = "SIM-меню",
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val now = System.currentTimeMillis()
                taps = if (now - last > 3000L) 1 else taps + 1
                last = now
                if (taps >= tapsRequired) { taps = 0; askPassword = true }
            },
            right = {
                Box(Modifier.background(Color.Black, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("4G", color = Yellow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        ) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .12f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Мой номер", color = Color.Black.copy(alpha = .6f), fontSize = 12.sp)
                    Text("+998 •• ••• •• ••", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text("Добро пожаловать!", color = Color.Black, fontSize = 13.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(menu.size) { i ->
                val m = menu[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC)
                        .clickable { if (m.opensData) onOpenData() else info = m }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Yellow), contentAlignment = Alignment.Center) {
                        Icon(m.icon, null, tint = Color.Black)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(m.sub, color = Color.Gray, fontSize = 13.sp)
                    }
                    Text("›", color = Yellow, fontSize = 26.sp)
                }
            }
        }
    }

    info?.let {
        AlertDialog(
            containerColor = CardC, onDismissRequest = { info = null },
            title = { Text(it.title) }, text = { Text(it.answer) },
            confirmButton = { SmallDialogButton("OK", { info = null }) }
        )
    }

    if (askPassword) {
        var pass by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            containerColor = CardC,
            onDismissRequest = { if (!checking) askPassword = false },
            title = { Text(if (hasKey) "Введите PIN" else "Создайте пароль") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it; error = null },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Пароль") }
                    )
                    if (!hasKey) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pass2, onValueChange = { pass2 = it; error = null },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Повторите пароль") }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Пароль нигде не хранится. Если его забыть, видео не восстановить.", color = Color.Gray, fontSize = 12.sp)
                    }
                    error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Red, fontSize = 13.sp) }
                    if (checking) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Yellow) }
                }
            },
            confirmButton = {
                SmallDialogButton(if (hasKey) "OK" else "Создать", enabled = !checking, onClick = {
                    scope.launch {
                        error = null
                        if (hasKey) {
                            checking = true
                            val ok = onUnlock(pass)
                            checking = false
                            if (ok) { askPassword = false; onUnlocked() } else error = "Неверно"
                        } else {
                            if (pass.length < 6) error = "Минимум 6 символов"
                            else if (pass != pass2) error = "Пароли не совпадают"
                            else {
                                checking = true
                                onSetup(pass)
                                checking = false
                                askPassword = false
                                onUnlocked()
                            }
                        }
                    }
                })
            },
            dismissButton = {
                Row {
                    if (!hasKey) SmallDialogButton("Из копии", { askPassword = false; onRestore() }, enabled = !checking, accent = false)
                    SmallDialogButton("Отмена", { askPassword = false }, enabled = !checking, accent = false)
                }
            }
        )
    }
}

// ---------- Сейф ----------
@Composable
fun QueueCard(q: QueueUi, onRunNow: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CardC, RoundedCornerShape(18.dp)).padding(14.dp)) {
        if (q.running) {
            Text("Идёт сжатие в фоне", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("${q.label} · ${(q.progress * 100).toInt()}%", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { q.progress }, modifier = Modifier.fillMaxWidth(), color = Yellow)
        } else {
            Text("В очереди: ${q.pending}", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Ждёт зарядки или запуска вручную", color = Color.Gray, fontSize = 13.sp)
        }
        Row {
            if (!q.running) SmallDialogButton("Запустить сейчас", onRunNow)
            SmallDialogButton("Очистить очередь", onClear, accent = false)
        }
    }
}

@Composable
fun VaultScreen(
    files: List<File>, savedTotal: Long, queue: QueueUi,
    onAdd: () -> Unit, onOpen: (File) -> Unit, onLock: () -> Unit,
    onBackup: () -> Unit, onRestore: () -> Unit, onChangePass: () -> Unit,
    onRunQueue: () -> Unit, onClearQueue: () -> Unit
) {
    val ctx = LocalContext.current
    val total = files.sumOf { it.length() - VaultCrypto.HEADER }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd, containerColor = Yellow, contentColor = Color.Black,
                shape = RoundedCornerShape(20.dp),
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Добавить", fontWeight = FontWeight.Bold) }
            )
        }
    ) { pad ->
        Column(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            YellowHeader(
                title = "Сейф", subtitle = "Видео и фото зашифрованы",
                right = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.Black) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Резервная копия") }, onClick = { menu = false; onBackup() })
                            DropdownMenuItem(text = { Text("Восстановить из копии") }, onClick = { menu = false; onRestore() })
                            DropdownMenuItem(text = { Text("Сменить пароль") }, onClick = { menu = false; onChangePass() })
                        }
                    }
                    TextButton(onClick = onLock) { Text("Закрыть", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            ) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Файлы", "${files.size}", Modifier.weight(1f))
                    StatTile("Занимают", Formatter.formatFileSize(ctx, total), Modifier.weight(1f))
                    StatTile("Сэкономлено", Formatter.formatFileSize(ctx, savedTotal), Modifier.weight(1f))
                }
            }
            if (queue.running || queue.pending > 0) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) { QueueCard(queue, onRunQueue, onClearQueue) }
            }
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StripeLogo(64.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("Пока пусто", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Нажми «Добавить»: видео или фото", color = Color.Gray)
                    }
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)
                ) {
                    items(files, key = { it.name }) { f -> VideoCard(f) { onOpen(f) } }
                }
            }
        }
    }
}

@Composable
fun VideoCard(file: File, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val photo = VaultCrypto.isPhoto(file)
    val meta by produceState<Pair<Bitmap?, Long>>(ThumbCache.get(file.name) ?: Pair(null, 0L), file) {
        val cached = ThumbCache.get(file.name)
        if (cached != null) { value = cached; return@produceState }
        val priv = Session.priv
        val r = if (priv == null) Pair(null, 0L) else if (photo) Photos.thumb(file, priv) else Frames.vaultThumb(file, priv)
        ThumbCache.put(file.name, r)
        value = r
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(20.dp)).background(CardC).clickable(onClick = onClick)) {
        meta.first?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)), startY = 150f)))
        if (!photo) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.align(Alignment.Center).size(46.dp)
                .background(Yellow.copy(alpha = .92f), CircleShape).padding(8.dp))
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Formatter.formatFileSize(ctx, file.length() - VaultCrypto.HEADER), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (photo) Text("ФОТО", color = Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            else if (meta.second > 0) Text(fmtDur(meta.second), color = Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Экран настройки сжатия ----------
@Composable
fun SetupScreen(
    infos: List<VideoInfo>,
    trims: Map<String, Trim>,
    settings: Settings, onSettings: (Settings) -> Unit,
    chargingOnly: Boolean, onCharging: (Boolean) -> Unit,
    onTrim: (VideoInfo, Trim?) -> Unit,
    onCancel: () -> Unit, onStart: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = { b: Long -> Formatter.formatFileSize(ctx, b) }
    BackHandler(onBack = onCancel)

    var trimFor by remember { mutableStateOf<VideoInfo?>(null) }
    var cmpBusy by remember { mutableStateOf(false) }
    var cmp by remember { mutableStateOf<CompareResult?>(null) }

    val plans = infos.map { Compressor.plan(it, settings, trims[it.key] ?: Trim()) }
    val before = infos.sumOf { it.size }
    val after = plans.sumOf { it.estSize }
    val saved = max(0L, before - after)
    val pct = if (before > 0) (saved * 100 / before).toInt() else 0
    val fromVault = infos.firstOrNull()?.vaultName != null

    Column(Modifier.fillMaxSize()) {
        YellowHeader(title = if (fromVault) "Пересжатие" else "Сжатие", subtitle = "${infos.size} видео")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            infos.forEachIndexed { i, info ->
                val plan = plans[i]
                val t = trims[info.key]
                Column(Modifier.fillMaxWidth().background(CardC, RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Text("Видео ${i + 1} · ${fmtDur(info.durationMs)} · ${info.w}×${info.h}" + (if (info.fps > 0) " · ${info.fps} fps" else ""),
                        color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Сейчас ${f(info.size)} → " + if (plan.transcode) "≈ ${f(plan.estSize)}" else "без изменений (уже H.265)",
                        color = Color.Gray, fontSize = 13.sp
                    )
                    if (t != null && t.endMs > 0) Text("Обрезка: ${fmtDur(t.startMs)} – ${fmtDur(t.endMs)}", color = Yellow, fontSize = 13.sp)
                    Row {
                        SmallDialogButton("Обрезать", { trimFor = info })
                        if (!fromVault) SmallDialogButton("Проверить качество", {
                            if (!cmpBusy) {
                                cmpBusy = true
                                scope.launch {
                                    cmp = Compressor.preview(ctx, info, settings)
                                    cmpBusy = false
                                }
                            }
                        }, enabled = !cmpBusy)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(CardC, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Text("Битрейт: ${(settings.factor * 100).toInt()}% от оригинала", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.factor, onValueChange = { onSettings(settings.copy(factor = it)) },
                    valueRange = 0.15f..1f,
                    colors = SliderDefaults.colors(thumbColor = Yellow, activeTrackColor = Yellow)
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quality.values().forEach {
                        FilterChip(selected = abs(settings.factor - it.factor) < 0.01f,
                            onClick = { onSettings(settings.copy(factor = it.factor)) }, label = { Text(it.label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Разрешение", color = Color.Gray, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Resolution.values().forEach {
                        FilterChip(selected = settings.resPx == it.px, onClick = { onSettings(settings.copy(resPx = it.px)) }, label = { Text(it.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Частота кадров", color = Color.Gray, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fps.values().forEach {
                        FilterChip(selected = settings.fps == it.fps, onClick = { onSettings(settings.copy(fps = it.fps)) }, label = { Text(it.label) })
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(Card2, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Row { Text("Сейчас", color = Color.Gray, modifier = Modifier.weight(1f)); Text(f(before), color = Color.White, fontWeight = FontWeight.SemiBold) }
                Row { Text("Станет ≈", color = Color.Gray, modifier = Modifier.weight(1f)); Text(f(after), color = Color.White, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Экономия", color = Color.Gray, modifier = Modifier.weight(1f))
                    Text("≈ ${f(saved)} ($pct%)", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Расчёт примерный: точный размер зависит от видео и кодировщика телефона.", color = Color.Gray, fontSize = 12.sp)
            }

            if (!fromVault) {
                Row(Modifier.fillMaxWidth().background(CardC, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Только на зарядке", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Сжатие пойдёт в фоне, когда телефон заряжается", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = chargingOnly, onCheckedChange = onCharging,
                        colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Color.Black))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(
                onClick = onStart, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black)
            ) { Text(if (fromVault) "Пересжать" else if (chargingOnly) "В очередь" else "Сжать", fontWeight = FontWeight.Bold) }
        }
    }

    trimFor?.let { info ->
        TrimDialog(info, trims[info.key], onSave = { onTrim(info, it); trimFor = null }, onDismiss = { trimFor = null })
    }
    if (cmpBusy) {
        AlertDialog(
            containerColor = CardC, onDismissRequest = {},
            title = { Text("Делаю тестовый фрагмент…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Yellow) },
            confirmButton = {}
        )
    }
    cmp?.let { c ->
        AlertDialog(
            containerColor = CardC, onDismissRequest = { cmp = null },
            title = { Text("До и после") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Оригинал", color = Color.Gray, fontSize = 12.sp)
                            c.orig?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Сжатое", color = Color.Gray, fontSize = 12.sp)
                            c.comp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(c.note, color = Color.Gray, fontSize = 12.sp)
                }
            },
            confirmButton = { SmallDialogButton("OK", { cmp = null }) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimDialog(info: VideoInfo, current: Trim?, onSave: (Trim?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dur = info.durationMs.coerceAtLeast(2000L).toFloat()
    var range by remember {
        mutableStateOf(
            (current?.startMs?.toFloat() ?: 0f)..(current?.endMs?.takeIf { it > 0 }?.toFloat() ?: dur)
        )
    }
    val f1 by produceState<Bitmap?>(null, (range.start / 250f).toLong()) {
        delay(250)
        value = withContext(Dispatchers.IO) { Compressor.frameAt(ctx, info, range.start.toLong()) }
    }
    val f2 by produceState<Bitmap?>(null, (range.endInclusive / 250f).toLong()) {
        delay(250)
        value = withContext(Dispatchers.IO) { Compressor.frameAt(ctx, info, (range.endInclusive - 100f).toLong().coerceAtLeast(0L)) }
    }
    AlertDialog(
        containerColor = CardC, onDismissRequest = onDismiss,
        title = { Text("Обрезка") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Начало ${fmtDur(range.start.toLong())}", color = Color.Gray, fontSize = 12.sp)
                        f1?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Конец ${fmtDur(range.endInclusive.toLong())}", color = Color.Gray, fontSize = 12.sp)
                        f2?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                RangeSlider(
                    value = range,
                    onValueChange = { r -> if (r.endInclusive - r.start >= 1000f) range = r },
                    valueRange = 0f..dur,
                    colors = SliderDefaults.colors(thumbColor = Yellow, activeTrackColor = Yellow)
                )
                Text("Останется ${fmtDur((range.endInclusive - range.start).toLong())} из ${fmtDur(info.durationMs)}", color = Color.Gray, fontSize = 12.sp)
            }
        },
        confirmButton = { SmallDialogButton("Готово", { onSave(Trim(range.start.toLong(), range.endInclusive.toLong())) }) },
        dismissButton = { Row { SmallDialogButton("Сбросить", { onSave(null) }, accent = false); SmallDialogButton("Отмена", onDismiss, accent = false) } }
    )
}

// ---------- Диалоги ----------
@Composable
fun ProgressDialog(status: String, progress: Float, onCancel: (() -> Unit)?) {
    AlertDialog(
        containerColor = CardC,
        onDismissRequest = {},
        title = { Text(if (progress < 0) "Подожди…" else "Работаю…") },
        text = {
            Column {
                Text(status)
                Spacer(Modifier.height(12.dp))
                if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Yellow)
                else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Yellow)
                    Spacer(Modifier.height(6.dp))
                    Text("${(progress * 100).toInt()}%", color = Color.Gray)
                }
            }
        },
        confirmButton = { if (onCancel != null) SmallDialogButton("Отмена", onCancel, accent = false) }
    )
}

@Composable
fun RestorePasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = CardC, onDismissRequest = onDismiss,
        title = { Text("Восстановление") },
        text = {
            Column {
                Text("Введите пароль, с которым делалась копия. Текущее содержимое сейфа будет заменено.", color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = pass, onValueChange = { pass = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль копии") })
            }
        },
        confirmButton = { SmallDialogButton("Восстановить", { onConfirm(pass) }, enabled = pass.isNotEmpty()) },
        dismissButton = { SmallDialogButton("Отмена", onDismiss, accent = false) }
    )
}

@Composable
fun ChangePasswordDialog(onConfirm: suspend (String, String) -> Boolean, onDismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        containerColor = CardC, onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Смена пароля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(old, { old = it; error = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Старый пароль") })
                OutlinedTextField(n1, { n1 = it; error = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Новый пароль") })
                OutlinedTextField(n2, { n2 = it; error = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Повторите новый") })
                error?.let { Text(it, color = Red, fontSize = 13.sp) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Yellow)
            }
        },
        confirmButton = {
            SmallDialogButton("Сменить", enabled = !busy, onClick = {
                if (n1.length < 6) error = "Минимум 6 символов"
                else if (n1 != n2) error = "Пароли не совпадают"
                else scope.launch {
                    busy = true
                    val ok = onConfirm(old, n1)
                    busy = false
                    if (ok) onDismiss() else error = "Старый пароль неверный"
                }
            })
        },
        dismissButton = { SmallDialogButton("Отмена", onDismiss, enabled = !busy, accent = false) }
    )
}

// ---------- Плеер ----------
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun setFullscreen(act: Activity, on: Boolean, landscape: Boolean) {
    val ctrl = WindowCompat.getInsetsController(act.window, act.window.decorView)
    if (on) {
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        act.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    } else {
        ctrl.show(WindowInsetsCompat.Type.systemBars())
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

@Composable
fun FullscreenIcon(exit: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val s = w * 0.30f
        val sw = 2.5.dp.toPx()
        val m = 2.dp.toPx()
        fun l(a: Offset, b: Offset) = drawLine(tint, a, b, strokeWidth = sw)
        if (!exit) {
            l(Offset(m, m + s), Offset(m, m)); l(Offset(m, m), Offset(m + s, m))
            l(Offset(w - m - s, m), Offset(w - m, m)); l(Offset(w - m, m), Offset(w - m, m + s))
            l(Offset(m, w - m - s), Offset(m, w - m)); l(Offset(m, w - m), Offset(m + s, w - m))
            l(Offset(w - m - s, w - m), Offset(w - m, w - m)); l(Offset(w - m, w - m), Offset(w - m, w - m - s))
        } else {
            val a = w * 0.36f
            val b = w * 0.64f
            l(Offset(a - s, a), Offset(a, a)); l(Offset(a, a), Offset(a, a - s))
            l(Offset(b, a - s), Offset(b, a)); l(Offset(b, a), Offset(b + s, a))
            l(Offset(a - s, b), Offset(a, b)); l(Offset(a, b), Offset(a, b + s))
            l(Offset(b + s, b), Offset(b, b)); l(Offset(b, b), Offset(b, b + s))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    file: File, onBack: () -> Unit, onShare: () -> Unit,
    onRecompress: () -> Unit, onTrim: () -> Unit, onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    var confirm by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var fsLandscape by remember { mutableStateOf(true) }
    val priv = Session.priv
    if (priv == null) { onBack(); return }
    val reader = remember(file) { VaultReader(file, priv) }
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            val factory = DataSource.Factory { VaultDataSource(reader) }
            setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("vault://${file.name}")))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release(); reader.close() } }
    // при выходе с экрана плеера возвращаем системные панели и ориентацию
    DisposableEffect(Unit) { onDispose { activity?.let { setFullscreen(it, false, false) } } }
    LaunchedEffect(fullscreen) { activity?.let { setFullscreen(it, fullscreen, fsLandscape) } }
    BackHandler { if (fullscreen) fullscreen = false else onBack() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Yellow, YellowDark)))
                    .statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.Black) }
                Text(Formatter.formatFileSize(ctx, file.length() - VaultCrypto.HEADER), color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onTrim) { Text("Обрезать", color = Color.Black, fontWeight = FontWeight.Bold) }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, null, tint = Color.Black) }
                IconButton(onClick = onRecompress) { Icon(Icons.Default.Refresh, null, tint = Color.Black) }
                IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, null, tint = Color.Black) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .5f))
                    .clickable {
                        if (!fullscreen) {
                            val vs = player.videoSize
                            fsLandscape = vs.width >= vs.height
                        }
                        fullscreen = !fullscreen
                    },
                contentAlignment = Alignment.Center
            ) { FullscreenIcon(fullscreen, Color.White) }
        }
    }
    if (confirm) {
        AlertDialog(
            containerColor = CardC,
            onDismissRequest = { confirm = false },
            title = { Text("Удалить видео?") },
            text = { Text("Оно будет удалено из сейфа навсегда.") },
            confirmButton = { SmallDialogButton("Удалить", { confirm = false; onDelete() }) },
            dismissButton = { SmallDialogButton("Отмена", { confirm = false }, accent = false) }
        )
    }
}

fun shareFile(ctx: Context, file: File, mime: String = "video/mp4") {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, "Поделиться"))
}

// ---------- Просмотр фото ----------
@Composable
fun ZoomableImage(bitmap: Bitmap, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(scale > 1.01f) { onZoomChanged(scale > 1.01f) }

    fun clamp(o: Offset, sc: Float): Offset {
        val mx = box.width * (sc - 1f) / 2f
        val my = box.height * (sc - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) { scale = 1f; off = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            off = if (scale <= 1.01f) Offset.Zero else clamp(off + pan, scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Image(
            bitmap.asImageBitmap(), null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = off.x; translationY = off.y
            },
            contentScale = ContentScale.Fit
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoScreen(
    files: List<File>, initial: File,
    onBack: () -> Unit, onShare: (File) -> Unit, onDelete: (File) -> Unit
) {
    val ctx = LocalContext.current
    val priv = Session.priv
    if (priv == null || files.isEmpty()) { onBack(); return }
    val pager = rememberPagerState(initialPage = files.indexOf(initial).coerceAtLeast(0)) { files.size }
    var zoomed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    BackHandler(onBack = onBack)
    val current = files.getOrNull(pager.currentPage) ?: files.first()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Yellow, YellowDark)))
                .statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.Black) }
            Text(
                "${pager.currentPage + 1} / ${files.size} · " + Formatter.formatFileSize(ctx, current.length() - VaultCrypto.HEADER),
                color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onShare(current) }) { Icon(Icons.Default.Share, null, tint = Color.Black) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, null, tint = Color.Black) }
        }
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            val f = files[page]
            val bmp by produceState<Bitmap?>(null, f) { value = Photos.load(f, priv) }
            val b = bmp
            if (b != null) ZoomableImage(b) { z -> if (page == pager.currentPage) zoomed = z }
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Yellow)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            containerColor = CardC,
            onDismissRequest = { confirm = false },
            title = { Text("Удалить фото?") },
            text = { Text("Оно будет удалено из сейфа навсегда.") },
            confirmButton = { SmallDialogButton("Удалить", { confirm = false; onDelete(current) }) },
            dismissButton = { SmallDialogButton("Отмена", { confirm = false }, accent = false) }
        )
    }
}
