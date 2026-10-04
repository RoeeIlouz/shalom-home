package com.saba.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF1B1B1B)
private val Paper = Color(0xFFF7F3EA)

@Composable
fun SabaApp(a: MainActivity) {
    val lang = a.settings.uiLang()
    MaterialTheme(colorScheme = lightColorScheme(background = Paper, surface = Paper)) {
        when (a.screen) {
            // Setup and the PIN pad follow the caregiver's chosen language and its reading direction.
            Screen.ADMIN, Screen.PIN -> CompositionLocalProvider(
                LocalUiLang provides lang,
                LocalLayoutDirection provides if (lang == UiLang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                if (a.screen == Screen.ADMIN) AdminScreen(a) else PinScreen(a)
            }
            else -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalUiLang provides UiLang.HE) {
                val onWallpaper = a.settings.showWallpaper && a.screen == Screen.HOME
                val bg = if (onWallpaper) {
                    // A soft dark wash at the top keeps the clock readable on any photo.
                    Modifier.background(Brush.verticalGradient(0f to Color(0x88000000), 0.35f to Color(0x22000000), 1f to Color(0x00000000)))
                } else Modifier.background(Paper)
                Box(Modifier.fillMaxSize().then(bg)) {
                    if (a.screen == Screen.ALL_APPS) AllAppsScreen(a) else HomeScreen(a, onWallpaper)
                    VoiceOverlay(a)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(a: MainActivity, onWallpaper: Boolean) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        Clock(onWallpaper, onSecretTaps = { a.screen = Screen.PIN })
        Spacer(Modifier.height(12.dp))
        val extras = a.settings.extras.filter { it.approved }
        if (extras.isEmpty()) {
            TileGrid(a, Modifier.weight(1f))
        } else {
            // Page 2 holds the widgets and shortcuts the caregiver approved. Pressing Home always returns to page 1.
            val pager = rememberPagerState(pageCount = { 2 })
            LaunchedEffect(a.homePage) { if (a.homePage != pager.currentPage) pager.scrollToPage(a.homePage) }
            LaunchedEffect(pager.currentPage) { a.homePage = pager.currentPage }
            HorizontalPager(pager, Modifier.weight(1f), pageSpacing = 16.dp) { page ->
                if (page == 0) TileGrid(a, Modifier.fillMaxSize()) else ExtrasPage(a, extras)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                repeat(2) { i ->
                    Box(
                        Modifier.padding(horizontal = 6.dp).size(if (i == pager.currentPage) 16.dp else 12.dp)
                            .clip(CircleShape).background(
                                when {
                                    onWallpaper && i == pager.currentPage -> Color.White
                                    onWallpaper -> Color(0x88FFFFFF)
                                    i == pager.currentPage -> Ink
                                    else -> Color(0x55000000)
                                }
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        TalkButton(a)
    }
}

@Composable
private fun TileGrid(a: MainActivity, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(a.settings.tiles, key = { _, t -> t.id }) { _, tile ->
            TileButton(tile, a.apps.firstOrNull { it.packageName == tile.target }) {
                a.open(Target.OfTile(tile))
            }
        }
    }
}

/** Widgets and shortcuts, one under the other. Nothing here reacts to long-press, so nothing can be moved. */
@Composable
private fun ExtrasPage(a: MainActivity, extras: List<Extra>) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(extras, key = { it.id }) { e ->
            when (e.kind) {
                ExtraKind.SHORTCUT -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1565C0))
                        .clickable { if (!a.extrasHost.launch(e)) a.toast("לא הצלחתי לפתוח") }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = remember(e.id) { a.extrasHost.icon(e) }
                    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(72.dp).clip(CircleShape))
                    else Icon(Icons.Filled.Phone, null, Modifier.size(72.dp), tint = Color.White)
                    Spacer(Modifier.width(18.dp))
                    Text(e.label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                ExtraKind.WIDGET -> {
                    val info = remember(e.widgetId) { a.extrasHost.widgetInfo(e.widgetId) }
                    if (info != null) {
                        val density = LocalDensity.current.density
                        val widthDp = LocalConfiguration.current.screenWidthDp - 32
                        // Give every widget a generous slot; resizable ones (contact cards, calendars) grow into it.
                        val heightDp = (info.minHeight / density).coerceIn(200f, 360f).toInt()
                        AndroidView(
                            factory = { ctx ->
                                a.extrasHost.widgetHost.createView(ctx, e.widgetId, info).apply {
                                    a.extrasHost.widgetManager.updateAppWidgetOptions(e.widgetId, Bundle().apply {
                                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                                    })
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(heightDp.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Big clock and date. Five quick taps open the PIN pad; a single stray tap does nothing. */
@Composable
private fun Clock(onWallpaper: Boolean, onSecretTaps: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val taps = remember { mutableListOf<Long>() }
    val he = Locale.forLanguageTag("he-IL")
    Column(
        Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember {
            androidx.compose.foundation.interaction.MutableInteractionSource()
        }) {
            val t = System.currentTimeMillis()
            taps.removeAll { t - it > 3000 }
            taps += t
            if (taps.size >= 5) {
                taps.clear()
                onSecretTaps()
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = if (onWallpaper) {
            TextStyle(color = Color.White, shadow = Shadow(Color(0xCC000000), Offset(0f, 3f), blurRadius = 10f))
        } else TextStyle(color = Ink)
        Text(SimpleDateFormat("HH:mm", he).format(now), fontSize = 72.sp, fontWeight = FontWeight.Bold, style = style)
        Text(SimpleDateFormat("EEEE, d בMMMM", he).format(now), fontSize = 26.sp, style = style)
    }
}

@Composable
private fun TileButton(tile: Tile, app: AppInfo?, onClick: () -> Unit) {
    Column(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(tile.color))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val iconMod = Modifier.size(84.dp)
        when {
            tile.kind == TileKind.CALL -> Icon(Icons.Filled.Phone, null, iconMod, tint = Color.White)
            tile.kind == TileKind.ALL_APPS -> Icon(Icons.Filled.Apps, null, iconMod, tint = Color.White)
            app?.icon != null -> Image(app.icon.toBitmap(192, 192).asImageBitmap(), null, iconMod)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            // Long names ("כל האפליקציות") shrink instead of being cut off.
            tile.label, color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = if (tile.label.length > 9) 24.sp else 30.sp,
            lineHeight = if (tile.label.length > 9) 27.sp else 32.sp,
            textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}

@Composable
private fun TalkButton(a: MainActivity) {
    val listening = a.voice is VoiceState.Listening
    Row(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(55.dp))
            .background(if (listening) Color(0xFFC62828) else Ink)
            // A light rim so the button still stands out on a dark wallpaper.
            .border(3.dp, Color(0x66FFFFFF), RoundedCornerShape(55.dp))
            .clickable { a.onTalkPressed() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Mic, null, Modifier.size(56.dp), tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(if (listening) "אני מקשיב..." else "דבר איתי", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VoiceOverlay(a: MainActivity) {
    val v = a.voice
    if (v is VoiceState.Idle || v is VoiceState.Listening && a.screen == Screen.HOME) {
        if (v is VoiceState.Listening) ListeningPulse(v.level)
        return
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { a.cancelVoice() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Paper)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (v) {
                is VoiceState.Thinking -> {
                    CircularProgressIndicator(Modifier.size(72.dp), strokeWidth = 8.dp, color = Ink)
                    Spacer(Modifier.height(20.dp))
                    Big("רגע, אני חושב...")
                }
                is VoiceState.Confirm -> {
                    Big(a.describe(v.target))
                    Spacer(Modifier.height(12.dp))
                    Text("${v.secondsLeft}", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BigButton("עכשיו", Color(0xFF2E7D32), Modifier.weight(1f)) { a.confirmNow() }
                        BigButton("ביטול", Color(0xFFC62828), Modifier.weight(1f)) { a.cancelVoice() }
                    }
                }
                is VoiceState.NotUnderstood -> {
                    Big(if (v.transcript.isBlank()) "לא שמעתי" else "לא הבנתי")
                    if (v.transcript.isNotBlank()) {
                        Text("\"${v.transcript}\"", fontSize = 22.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(20.dp))
                    BigButton("לנסות שוב", Ink, Modifier.fillMaxWidth()) { a.onTalkPressed() }
                }
                is VoiceState.NoModel -> {
                    Big("הדיבור עוד לא מוכן")
                    Text("צריך להוריד מודל דיבור בהגדרות", fontSize = 18.sp, color = Color.Gray)
                    Spacer(Modifier.height(20.dp))
                    BigButton("סגור", Ink, Modifier.fillMaxWidth()) { a.cancelVoice() }
                }
                is VoiceState.Listening -> { // listening while on the app list
                    ListeningPulse(v.level)
                    Big("אני מקשיב...")
                }
                VoiceState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ListeningPulse(level: Float) {
    val s by animateFloatAsState(1f + level * 0.6f, label = "pulse")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.padding(bottom = 150.dp).size(70.dp).scale(s).clip(CircleShape).background(Color(0x55C62828)),
        )
    }
}

@Composable
private fun Big(text: String) =
    Text(text, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)

@Composable
private fun BigButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(84.dp).clip(RoundedCornerShape(24.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AllAppsScreen(a: MainActivity) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        BigButton("חזרה", Ink, Modifier.fillMaxWidth()) { a.screen = Screen.HOME }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(a.apps, key = { it.packageName }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .clickable { a.open(Target.OfApp(app)) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    app.icon?.let { Image(it.toBitmap(144, 144).asImageBitmap(), null, Modifier.size(60.dp)) }
                    Spacer(Modifier.width(18.dp))
                    Text(app.label, fontSize = 28.sp, color = Ink)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TalkButton(a)
    }
}

@Composable
private fun PinScreen(a: MainActivity) {
    var entered by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val locked = now < a.pinLockedUntil
    LaunchedEffect(a.pinLockedUntil) {
        while (System.currentTimeMillis() < a.pinLockedUntil) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }
    LaunchedEffect(entered) {
        if (entered.length >= a.settings.pin.length) {
            if (!a.tryPin(entered)) {
                delay(300)
                entered = ""
            }
        }
    }
    LaunchedEffect(Unit) { // Grandpa landed here by accident: drift back home on its own
        delay(20_000)
        if (a.screen == Screen.PIN) a.screen = Screen.HOME
    }
    Column(
        Modifier.fillMaxSize().background(Paper).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("Setup PIN", "קוד להגדרות"), fontSize = 28.sp, color = Ink)
        if (locked) {
            val secs = (a.pinLockedUntil - now) / 1000 + 1
            Text(tr("Locked for ${secs}s", "נעול ל-$secs שניות"), fontSize = 24.sp, color = Color(0xFFC62828))
        } else {
            Text("•".repeat(entered.length).padEnd(a.settings.pin.length, '○'), fontSize = 40.sp, color = Ink)
        }
        Spacer(Modifier.height(16.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(84.dp).clip(CircleShape).background(Color.White).clickable {
                            when (k) {
                                "✕" -> a.screen = Screen.HOME
                                "⌫" -> entered = entered.dropLast(1)
                                else -> if (!locked) entered += k
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, fontSize = 32.sp, color = Ink) }
                }
            }
        }
    }
}
