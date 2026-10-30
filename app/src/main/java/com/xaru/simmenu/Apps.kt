package com.xaru.simmenu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(val pkg: String, val label: String)

/** Список отмеченных приложений («Данные») и поиск установленных. Пароль не нужен. */
object AppPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun chosen(ctx: Context): Set<String> = HashSet(p(ctx).getStringSet("chosen", emptySet()) ?: emptySet())

    fun save(ctx: Context, s: Set<String>) {
        p(ctx).edit().putStringSet("chosen", HashSet(s)).apply()
    }

    /** Все приложения, у которых есть значок запуска. Скрытые лаунчером (MIUI «Скрытые приложения») тоже попадают сюда. */
    fun installed(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}

private val iconCache = ConcurrentHashMap<String, Bitmap>()

@Composable
private fun AppIcon(pkg: String, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(iconCache[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                try {
                    ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).also { iconCache[pkg] = it }
                } catch (_: Exception) { null }
            }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Card2)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
    }
}

private fun launchApp(ctx: Context, pkg: String) {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (i == null) {
        Toast.makeText(ctx, "Не удалось открыть приложение", Toast.LENGTH_SHORT).show()
        return
    }
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (_: Exception) {
        Toast.makeText(ctx, "Не удалось открыть приложение", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<AppEntry>?>(null) }
    var chosen by remember { mutableStateOf(AppPrefs.chosen(ctx)) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { AppPrefs.installed(ctx) }
        all = list
        if (AppPrefs.chosen(ctx).isEmpty()) editing = true   // первый запуск: сразу выбор
    }
    DisposableEffect(Unit) { onDispose { iconCache.clear() } }
    BackHandler { if (editing && chosen.isNotEmpty()) editing = false else onBack() }

    val list = all
    val shown = remember(list, chosen) { list?.filter { it.pkg in chosen }.orEmpty() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        YellowHeader(
            title = "Данные",
            subtitle = if (editing) "Отметь нужные приложения" else "Мои приложения",
            right = {
                if (editing) {
                    TextButton(onClick = { editing = false }) { Text("Готово", color = Color.Black, fontWeight = FontWeight.Bold) }
                } else {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, null, tint = Color.Black) }
                    TextButton(onClick = onBack) { Text("Закрыть", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        )
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Yellow)
            }
            editing -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(list, key = { it.pkg }) { a ->
                    val on = a.pkg in chosen
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable {
                                chosen = if (on) chosen - a.pkg else chosen + a.pkg
                                AppPrefs.save(ctx, chosen)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(a.pkg, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Checkbox(
                            checked = on, onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Yellow, checkmarkColor = Color.Black, uncheckedColor = Color.Gray)
                        )
                    }
                }
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Пока пусто", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Нажми на карандаш и отметь приложения", color = Color.Gray)
                }
            }
            else -> LazyVerticalGrid(
                GridCells.Adaptive(84.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(shown, key = { it.pkg }) { a ->
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp)).clickable { launchApp(ctx, a.pkg) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(a.pkg, 60.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(a.label, color = Color.White, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
