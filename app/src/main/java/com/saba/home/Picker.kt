package com.saba.home

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class WidgetGroup(val packageName: String, val appLabel: String, val appIcon: Drawable?, val widgets: List<AppWidgetProviderInfo>)
private data class ShortcutGroup(val app: AppInfo, val shortcuts: List<ShortcutInfo>)

/**
 * Full-screen chooser for the second page, laid out like a phone's own widget picker:
 * one section per app, a real preview of each widget, and its size in home-screen cells.
 */
@Composable
fun AddToPage2Screen(a: MainActivity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }

    val widgetGroups by produceState<List<WidgetGroup>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            a.extrasHost.providers().groupBy { it.provider.packageName }.map { (pkg, list) ->
                val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                WidgetGroup(
                    packageName = pkg,
                    appLabel = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg,
                    appIcon = appInfo?.let { pm.getApplicationIcon(it) },
                    widgets = list,
                )
            }.sortedBy { it.appLabel.lowercase() }
        }
    }
    val makers by produceState<List<LauncherActivityInfo>?>(null) {
        value = withContext(Dispatchers.IO) { a.extrasHost.shortcutMakers() }
    }
    // Samsung can report no shortcut permission even for the default home app, so this only decides
    // whether app shortcuts can be listed; shortcut makers are always offered (they have a fallback).
    val canShortcuts = a.shortcutStatus.second
    val shortcutGroups by produceState<List<ShortcutGroup>?>(null) {
        value = if (!canShortcuts) emptyList() else withContext(Dispatchers.IO) {
            a.apps.map { app -> ShortcutGroup(app, a.extrasHost.appShortcuts(app.packageName)) }
                .filter { it.shortcuts.isNotEmpty() }
        }
    }

    fun matches(vararg text: CharSequence?) =
        query.isBlank() || text.any { it?.toString()?.contains(query.trim(), ignoreCase = true) == true }

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F4F4)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "חזרה")) }
            Text(tr("Add to the second page", "הוספה לעמוד השני"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text(tr("Search, e.g. contact, clock, weather", "חיפוש, למשל איש קשר, שעון, מזג אוויר")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp), containerColor = Color(0xFFF4F4F4)) {
            Tab(tab == 0, { tab = 0 }, text = { Text(tr("Widgets", "ווידג'טים"), fontSize = 16.sp) })
            Tab(tab == 1, { tab = 1 }, text = { Text(tr("Shortcuts", "קיצורי דרך"), fontSize = 16.sp) })
        }

        if (tab == 0) {
            val groups = widgetGroups
            if (groups == null) Loading() else LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val shown = groups.mapNotNull { g ->
                    val w = g.widgets.filter { matches(it.loadLabel(ctx.packageManager), g.appLabel) }
                    if (w.isEmpty()) null else g.copy(widgets = w)
                }
                if (shown.isEmpty()) item { Empty(tr("No widgets match", "לא נמצאו ווידג'טים")) }
                // Keyed by package: several Samsung apps share a display name, and duplicate keys crash the list.
                items(shown, key = { it.packageName }) { g ->
                    Column {
                        AppHeader(g.appIcon, g.appLabel)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 8.dp)) {
                            items(g.widgets, key = { it.provider.flattenToString() }) { w ->
                                WidgetCard(ctx, w) {
                                    onClose()
                                    a.addWidget(w)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!a.shortcutStatus.first) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFFFF3E0)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                tr("Android only lets the phone's home app add shortcuts. Make Shalom Home the home app, then come back here.",
                                    "אנדרואיד מאפשר רק לאפליקציית מסך הבית להוסיף קיצורים. צריך להגדיר את Shalom Home כמסך הבית ולחזור לכאן."),
                                fontSize = 15.sp,
                            )
                            Button(onClick = {
                                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                            }) { Text(tr("Make default home", "הגדרה כמסך בית")) }
                        }
                    }
                }
                item {
                    Text(
                        tr("Ask a question first, e.g. which contact to call", "שואלים שאלה קודם, למשל למי להתקשר"),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0),
                    )
                }
                val m = makers
                if (m == null) item { Loading() }
                else {
                    val shown = m.filter { matches(it.label, it.applicationInfo.loadLabel(ctx.packageManager)) }
                    if (shown.isEmpty()) item { Empty(tr("None on this phone", "אין בטלפון הזה")) }
                    items(shown, key = { it.componentName.flattenToString() }) { info ->
                        BigRow(
                            icon = remember(info) { info.getIcon(0) },
                            title = info.label.toString(),
                            subtitle = info.applicationInfo.loadLabel(ctx.packageManager).toString(),
                        ) {
                            onClose()
                            a.runShortcutMaker(info)
                        }
                    }
                }
                item {
                    Text(
                        tr("App shortcuts", "קיצורים של אפליקציות"),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (!canShortcuts) {
                    item { Empty(tr("Available once Shalom Home is the default home app", "זמין אחרי ש-Shalom Home מוגדר כמסך הבית")) }
                } else {
                    val groups = shortcutGroups
                    if (groups == null) item { Loading() }
                    else {
                        val shown = groups.mapNotNull { g ->
                            val sc = g.shortcuts.filter { matches(it.shortLabel, it.longLabel, g.app.label) }
                            if (sc.isEmpty()) null else g.copy(shortcuts = sc)
                        }
                        items(shown, key = { "sc_" + it.app.packageName }) { g ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppHeader(g.app.icon, g.app.label)
                                g.shortcuts.forEach { sc ->
                                    BigRow(
                                        icon = remember(sc.id) { a.extrasHost.shortcutIcon(sc) },
                                        title = (sc.shortLabel ?: sc.longLabel ?: "").toString(),
                                        subtitle = g.app.label,
                                    ) {
                                        onClose()
                                        a.addAppShortcut(sc)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(icon: Drawable?, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Image(rememberBitmap(it, 96), null, Modifier.size(32.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** A widget's preview at roughly its real proportions, with its name and size underneath. */
@Composable
private fun WidgetCard(ctx: Context, w: AppWidgetProviderInfo, onPick: () -> Unit) {
    val density = ctx.resources.displayMetrics.density
    val preview by produceState<ImageBitmap?>(null, w) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = w.loadPreviewImage(ctx, 0) ?: w.loadIcon(ctx, 0)
                d?.let { drawableToBitmap(it, 480) }
            }.getOrNull()
        }
    }
    val (cw, ch) = cells(w, density)
    Column(
        Modifier
            .width(170.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .clickable(onClick = onPick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE8EAF0)),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { Image(it, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) }
                ?: CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(w.loadLabel(ctx.packageManager), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("$cw × $ch", fontSize = 13.sp, color = Color.Gray)
    }
}

@Composable
private fun BigRow(icon: Drawable?, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(rememberBitmap(it, 144), null, Modifier.size(48.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 13.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
}

@Composable
private fun Empty(text: String) = Text(text, fontSize = 15.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))

@Composable
private fun rememberBitmap(d: Drawable, px: Int): ImageBitmap =
    remember(d) { runCatching { drawableToBitmap(d, px) }.getOrElse { ImageBitmap(1, 1) } }

/** Keeps the drawable's aspect ratio; some previews report no intrinsic size. */
private fun drawableToBitmap(d: Drawable, maxPx: Int): ImageBitmap {
    val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxPx
    val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxPx
    val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
    return d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
}

/** Widget size in home-screen cells, the way phone launchers label it ("2 × 2"). */
private fun cells(w: AppWidgetProviderInfo, density: Float): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && w.targetCellWidth > 0 && w.targetCellHeight > 0) {
        return w.targetCellWidth to w.targetCellHeight
    }
    fun toCells(px: Int) = (((px / density) + 30) / 70).toInt().coerceAtLeast(1)
    return toCells(w.minWidth) to toCells(w.minHeight)
}
