package com.saba.home

import android.Manifest
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** The caregiver's side: everything that can change the layout or the contacts lives behind the PIN. */
@Composable
fun AdminScreen(a: MainActivity) {
    // The default PIN is public knowledge (it is in this file), so setup stays closed until it is replaced.
    if (!a.settings.pinChosen) {
        ChoosePinScreen(
            title = tr("Choose a setup PIN first", "קודם בוחרים קוד להגדרות"),
            onCancel = { a.screen = Screen.HOME },
            onChosen = { a.updateSettings(a.settings.copy(pin = it, pinChosen = true)) },
        )
        return
    }

    var addingToPage2 by remember { mutableStateOf(false) }
    if (addingToPage2) {
        AddToPage2Screen(a, onClose = { addingToPage2 = false })
        return
    }

    val ctx = LocalContext.current
    val s = a.settings
    var editingTile by remember { mutableStateOf<Tile?>(null) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var pickingApp by remember { mutableStateOf(false) }
    var changingPin by remember { mutableStateOf(false) }
    val progress = a.downloads

    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                editingContact = Contact("c_${System.currentTimeMillis()}", c.getString(0) ?: "", c.getString(1) ?: "")
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    fun saveTiles(tiles: List<Tile>) = a.updateSettings(s.copy(tiles = tiles))
    fun tileFor(c: Contact) = s.tiles.firstOrNull { it.kind == TileKind.CALL && it.target == c.id }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Shalom Home setup", "הגדרות Shalom Home"), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Button(onClick = { a.screen = Screen.HOME }) { Text(tr("Done", "סיום")) }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Language", "שפה"), fontSize = 15.sp)
                    listOf("he" to "עברית", "en" to "English", "auto" to tr("Phone's", "כמו בטלפון")).forEach { (code, name) ->
                        FilterChip(selected = s.uiLanguage == code, onClick = { a.updateSettings(s.copy(uiLanguage = code)) },
                            label = { Text(name) })
                    }
                }
            }

            item { Section(tr("People he can call by voice (${s.contacts.size}/$MAX_CONTACTS)",
                "אנשים שאפשר להתקשר אליהם בקול (${s.contacts.size}/$MAX_CONTACTS)")) }
            item {
                Hint(tr("He can say \"תתקשר ל...\" with the name or any of the other words. " +
                    "Turn on \"Tile\" to also give someone a big button.",
                    "אפשר להגיד \"תתקשר ל...\" עם השם או כל אחת מהמילים הנוספות. " +
                        "\"אריח\" מוסיף גם כפתור גדול במסך הבית."))
            }
            items(s.contacts, key = { it.id }) { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Hint(c.number + if (c.aliases.isNotEmpty()) "  ·  ${tr("also", "גם")}: ${c.aliases.joinToString(", ")}" else "")
                    }
                    Text(tr("Tile", "אריח"), fontSize = 13.sp)
                    Switch(checked = tileFor(c) != null, onCheckedChange = { on ->
                        saveTiles(
                            if (on) s.tiles + Tile("call_${c.id}", TileKind.CALL, c.name, c.id,
                                color = TileColors[s.tiles.size % TileColors.size])
                            else s.tiles.filterNot { it.kind == TileKind.CALL && it.target == c.id }
                        )
                    })
                    IconButton(onClick = { editingContact = c }) { Icon(Icons.Filled.Edit, tr("Edit", "עריכה")) }
                    IconButton(onClick = {
                        a.updateSettings(s.copy(
                            contacts = s.contacts - c,
                            tiles = s.tiles.filterNot { it.kind == TileKind.CALL && it.target == c.id },
                        ))
                    }) { Icon(Icons.Filled.Delete, tr("Delete", "מחיקה")) }
                }
            }
            item {
                val full = s.contacts.size >= MAX_CONTACTS
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !full, onClick = {
                        contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }) { Text(tr("+ From contacts", "+ מאנשי הקשר")) }
                    OutlinedButton(enabled = !full, onClick = {
                        editingContact = Contact("c_${System.currentTimeMillis()}", "", "")
                    }) { Text(tr("+ Type a number", "+ הקלדת מספר")) }
                }
            }

            item { Section(tr("Home screen tiles (he cannot move or delete these)", "אריחים במסך הבית (הוא לא יכול להזיז או למחוק)")) }
            items(s.tiles, key = { it.id }) { t ->
                val i = s.tiles.indexOf(t)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(Color(t.color), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Hint(when (t.kind) {
                            TileKind.CALL -> tr("Calls", "מתקשר ל") + " " + (a.contactFor(t)?.name ?: "?")
                            TileKind.APP -> a.apps.firstOrNull { it.packageName == t.target }?.label ?: t.target
                            TileKind.ALL_APPS -> tr("All apps list", "רשימת כל האפליקציות")
                        })
                    }
                    IconButton(enabled = i > 0, onClick = {
                        saveTiles(s.tiles.toMutableList().apply { add(i - 1, removeAt(i)) })
                    }) { Icon(Icons.Filled.ArrowUpward, tr("Up", "למעלה")) }
                    IconButton(enabled = i < s.tiles.size - 1, onClick = {
                        saveTiles(s.tiles.toMutableList().apply { add(i + 1, removeAt(i)) })
                    }) { Icon(Icons.Filled.ArrowDownward, tr("Down", "למטה")) }
                    IconButton(onClick = { editingTile = t }) { Icon(Icons.Filled.Edit, tr("Edit", "עריכה")) }
                    IconButton(onClick = { saveTiles(s.tiles - t) }) { Icon(Icons.Filled.Delete, tr("Delete", "מחיקה")) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingApp = true }) { Text(tr("+ App", "+ אפליקציה")) }
                    if (s.tiles.none { it.kind == TileKind.ALL_APPS }) {
                        OutlinedButton(onClick = {
                            saveTiles(s.tiles + Tile("all_apps", TileKind.ALL_APPS, "כל האפליקציות", color = 0xFF455A64))
                        }) { Text(tr("+ All apps", "+ כל האפליקציות")) }
                    }
                }
            }

            item { Section(tr("Second page: widgets and shortcuts", "עמוד שני: ווידג'טים וקיצורי דרך")) }
            val pending = s.extras.filter { !it.approved }
            if (pending.isNotEmpty()) {
                item {
                    Text(tr("Awaiting your approval", "ממתינים לאישור שלך"), fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                }
                items(pending, key = { "p_" + it.id }) { e ->
                    ExtraRow(a, e) {
                        TextButton(onClick = { a.approveExtra(e) }) { Text(tr("Approve", "אישור")) }
                        TextButton(onClick = { a.removeExtra(e) }) { Text(tr("Reject", "דחייה"), color = Color(0xFFC62828)) }
                    }
                }
            }
            items(s.extras.filter { it.approved }, key = { it.id }) { e ->
                val i = s.extras.indexOf(e)
                ExtraRow(a, e) {
                    IconButton(enabled = i > 0, onClick = {
                        a.updateSettings(s.copy(extras = s.extras.toMutableList().apply { add(i - 1, removeAt(i)) }))
                    }) { Icon(Icons.Filled.ArrowUpward, tr("Up", "למעלה")) }
                    IconButton(onClick = { a.removeExtra(e) }) { Icon(Icons.Filled.Delete, tr("Remove", "הסרה")) }
                }
            }
            item {
                Button(onClick = { addingToPage2 = true }) { Text(tr("+ Add a widget or shortcut", "+ הוספת ווידג'ט או קיצור דרך")) }
            }
            item {
                Hint(tr("Shortcuts added from other apps (Contacts > Add to Home screen) wait here for your approval.",
                    "קיצורים שנוספים מאפליקציות אחרות (אנשי קשר > הוספה למסך הבית) ממתינים כאן לאישור שלך."))
            }

            item { Section(tr("Wallpaper", "רקע")) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Show the phone's wallpaper", "להציג את תמונת הרקע של הטלפון"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = s.showWallpaper, onCheckedChange = { a.updateSettings(s.copy(showWallpaper = it)) })
                }
            }
            item {
                val chooserTitle = tr("Wallpaper", "רקע")
                OutlinedButton(enabled = s.showWallpaper, onClick = {
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), chooserTitle)) }
                }) { Text(tr("Change wallpaper", "החלפת תמונת רקע")) }
            }

            item { Section(tr("Voice (runs on this phone, no internet)", "קול (רץ על הטלפון, בלי אינטרנט)")) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("He speaks", "הוא מדבר"), fontSize = 15.sp)
                    listOf("he" to tr("Hebrew", "עברית"), "fr" to tr("French", "צרפתית"), "auto" to tr("Auto", "אוטומטי"))
                        .forEach { (code, name) ->
                            FilterChip(selected = s.language == code, onClick = { a.updateSettings(s.copy(language = code)) },
                                label = { Text(name) })
                        }
                }
            }
            items(ModelCatalog.all, key = { it.fileName }) { m ->
                a.modelsVersion // recompose after downloads
                val ready = ModelCatalog.isReady(ctx, m)
                val isWhisper = m in ModelCatalog.whisperModels
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isWhisper) {
                        RadioButton(selected = s.whisperModel == m.fileName, enabled = ready,
                            onClick = { a.updateSettings(s.copy(whisperModel = m.fileName)) })
                    } else {
                        Checkbox(checked = s.useLlm, onCheckedChange = { a.updateSettings(s.copy(useLlm = it)) })
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr(m.title, m.titleHe), fontSize = 15.sp)
                        progress[m.fileName]?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
                    }
                    when {
                        ready -> Text(tr("Ready", "מוכן"), color = Color(0xFF2E7D32))
                        progress.containsKey(m.fileName) -> Text("${((progress[m.fileName] ?: 0f) * 100).toInt()}%")
                        else -> TextButton(onClick = { a.download(m) }) { Text(tr("Download", "הורדה")) }
                    }
                }
            }
            item {
                Hint(tr("Last request", "בקשה אחרונה") + ": " +
                    a.lastDebug.ifEmpty { tr("(press Talk on the home screen)", "(לוחצים על \"דבר איתי\" במסך הבית)") })
            }

            item { Section(tr("Phone and lock", "טלפון ונעילה")) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE))
                    }) { Text(tr("Allow mic + calls", "הרשאות מיקרופון ושיחות")) }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
                    }) { Text(tr("Make default home", "הגדרה כמסך בית")) }
                }
            }
            item {
                Hint(tr("Setup opens with 5 quick taps on the clock, then the PIN. 3 wrong PINs lock the pad for a minute.",
                    "ההגדרות נפתחות ב-5 הקשות מהירות על השעון ואז הקוד. 3 קודים שגויים נועלים לדקה."))
            }
            item { OutlinedButton(onClick = { changingPin = true }) { Text(tr("Change PIN", "החלפת קוד")) } }
            item {
                val (home, perm) = a.shortcutStatus
                Hint(tr("Diagnostics", "אבחון") + ": " +
                    tr("home app", "מסך בית") + " " + (if (home) "✓" else "✗") + "  ·  " +
                    tr("shortcut permission", "הרשאת קיצורים") + " " + (if (perm) "✓" else "✗") +
                    "  ·  Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
            if (a.lastCrash.isNotEmpty()) {
                item { Section(tr("Last crash (send this to the developer)", "קריסה אחרונה (לשלוח למפתח)")) }
                item {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(a.lastCrash, fontSize = 11.sp, color = Color(0xFFC62828), lineHeight = 13.sp)
                    }
                }
                item { TextButton(onClick = { a.clearCrash() }) { Text(tr("Clear", "ניקוי")) } }
            }
            item { Section(tr("About", "אודות")) }
            item { About() }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    editingContact?.let { c ->
        ContactEditor(c, onDismiss = { editingContact = null }) { updated ->
            val exists = s.contacts.any { it.id == updated.id }
            a.updateSettings(s.copy(
                contacts = if (exists) s.contacts.map { if (it.id == updated.id) updated else it } else s.contacts + updated,
                // Keep a tile's label in step with a renamed contact.
                tiles = s.tiles.map { t ->
                    if (t.kind == TileKind.CALL && t.target == updated.id && exists) t.copy(label = updated.name) else t
                },
            ))
            editingContact = null
        }
    }
    editingTile?.let { t ->
        TileEditor(t, onDismiss = { editingTile = null }) { updated ->
            val exists = s.tiles.any { it.id == updated.id }
            saveTiles(if (exists) s.tiles.map { if (it.id == updated.id) updated else it } else s.tiles + updated)
            editingTile = null
        }
    }
    if (pickingApp) {
        AppPicker(a.apps, onDismiss = { pickingApp = false }) { app ->
            pickingApp = false
            editingTile = Tile(
                id = "app_${app.packageName}", kind = TileKind.APP, label = app.label, target = app.packageName,
                color = TileColors[s.tiles.size % TileColors.size],
            )
        }
    }
    if (changingPin) {
        AlertDialog(
            onDismissRequest = { changingPin = false },
            confirmButton = {},
            text = {
                ChoosePinScreen(tr("New setup PIN", "קוד חדש להגדרות"), onCancel = { changingPin = false }) {
                    a.updateSettings(s.copy(pin = it, pinChosen = true))
                    changingPin = false
                }
            },
        )
    }
}

@Composable
private fun ExtraRow(a: MainActivity, e: Extra, actions: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = remember(e.id) { a.extrasHost.icon(e) }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.label, fontSize = 17.sp)
            Hint(if (e.kind == ExtraKind.WIDGET) tr("Widget", "ווידג'ט") else tr("Shortcut", "קיצור דרך"))
        }
        actions()
    }
}

/** Enter a new PIN twice. Rejects short PINs and obvious ones a curious grandparent might try. */
@Composable
private fun ChoosePinScreen(title: String, onCancel: () -> Unit, onChosen: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val weak = setOf("0000", "1234", "1111", "4321", "2580", "000000", "123456")
    val problem = when {
        first.length < 4 -> tr("At least 4 digits", "לפחות 4 ספרות")
        first in weak || first.toSet().size == 1 -> tr("Too easy to guess", "קל מדי לניחוש")
        second.isNotEmpty() && second != first -> tr("PINs do not match", "הקודים לא תואמים")
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().background(Color.White).systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tr("Only you should know it. Grandpa never needs it.", "רק את/ה צריכ/ה לדעת אותו. סבא לא צריך אותו."),
            fontSize = 14.sp, color = Color.Gray)
        val pinField = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        OutlinedTextField(first, { first = it.filter(Char::isDigit).take(8) }, label = { Text(tr("New PIN", "קוד חדש")) },
            singleLine = true, keyboardOptions = pinField, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(second, { second = it.filter(Char::isDigit).take(8) }, label = { Text(tr("Repeat PIN", "שוב את הקוד")) },
            singleLine = true, keyboardOptions = pinField, visualTransformation = PasswordVisualTransformation())
        if (first.isNotEmpty()) problem?.let { Text(it, color = Color(0xFFC62828), fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text(tr("Cancel", "ביטול")) }
            Button(enabled = problem == null && second == first, onClick = { onChosen(first) }) { Text(tr("Save PIN", "שמירת קוד")) }
        }
    }
}

private const val SOURCE_URL = "https://github.com/RoeeIlouz/shalom-home"
private const val STUDIO_URL = "https://rocisapps.com"

@Composable
private fun About() {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    fun open(url: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(Color(0xFFF5F5F7))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            androidx.compose.ui.res.painterResource(R.drawable.rocisapps_lockup),
            contentDescription = "ROCIs Apps",
            modifier = Modifier.fillMaxWidth(0.75f).clickable { open(STUDIO_URL) },
        )
        Text("Shalom Home $version", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            tr("Made for my grandpa Shalom, for Hacktoberfest 2026 (DEV Weekend Challenge: Build for a Friend).",
                "נבנה בשביל סבא שלום, במסגרת Hacktoberfest 2026 (אתגר DEV: Build for a Friend)."),
            fontSize = 14.sp,
        )
        Text(
            tr("Speech and understanding run on this phone with open models: Whisper (whisper.cpp) and Gemma 3 1B (llama.cpp).",
                "הדיבור וההבנה רצים על הטלפון עם מודלים פתוחים: Whisper (whisper.cpp) ו-Gemma 3 1B (llama.cpp)."),
            fontSize = 13.sp, color = Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open(SOURCE_URL) }) { Text(tr("Source code", "קוד מקור")) }
            OutlinedButton(onClick = { open(STUDIO_URL) }) { Text("rocisapps.com") }
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), modifier = Modifier.padding(top = 14.dp))

@Composable
private fun Hint(text: String) = Text(text, fontSize = 13.sp, color = Color.Gray)

@Composable
private fun ContactEditor(contact: Contact, onDismiss: () -> Unit, onSave: (Contact) -> Unit) {
    var name by remember { mutableStateOf(contact.name) }
    var number by remember { mutableStateOf(contact.number) }
    var aliases by remember { mutableStateOf(contact.aliases.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Person he can call", "מישהו שאפשר להתקשר אליו")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name he uses (e.g. מיכל)", "השם שהוא משתמש בו (למשל מיכל)")) },
                    singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text(tr("Phone number", "מספר טלפון")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(aliases, { aliases = it },
                    label = { Text(tr("Other words he says for them, comma separated (e.g. הבת שלי, ma fille)",
                        "מילים נוספות שהוא אומר, מופרדות בפסיק (למשל הבת שלי, ma fille)")) })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && number.count(Char::isDigit) >= 3, onClick = {
                onSave(contact.copy(
                    name = name.trim(),
                    number = number.trim(),
                    aliases = aliases.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() },
                ))
            }) { Text(tr("Save", "שמירה")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "ביטול")) } },
    )
}

@Composable
private fun TileEditor(tile: Tile, onDismiss: () -> Unit, onSave: (Tile) -> Unit) {
    var label by remember { mutableStateOf(tile.label) }
    var aliases by remember { mutableStateOf(tile.aliases.joinToString(", ")) }
    var color by remember { mutableLongStateOf(tile.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Tile", "אריח")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text(tr("Name on the tile", "השם על האריח")) }, singleLine = true)
                if (tile.kind == TileKind.APP) {
                    OutlinedTextField(aliases, { aliases = it },
                        label = { Text(tr("Other words he uses for this app, comma separated",
                            "מילים נוספות שהוא אומר לאפליקציה, מופרדות בפסיק")) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TileColors.forEach { c ->
                        Box(Modifier.size(if (c == color) 34.dp else 26.dp).background(Color(c), CircleShape)
                            .clickable { color = c })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = {
                onSave(tile.copy(
                    label = label.trim(),
                    aliases = aliases.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() },
                    color = color,
                ))
            }) { Text(tr("Save", "שמירה")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "ביטול")) } },
    )
}

/** Full list of apps with their icons, for adding a tile. */
@Composable
private fun AppPicker(apps: List<AppInfo>, onDismiss: () -> Unit, onPick: (AppInfo) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Choose an app", "בחירת אפליקציה")) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text(tr("Search", "חיפוש")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) },
                        key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            app.icon?.let { Image(remember(app.packageName) { it.toBitmap(96, 96).asImageBitmap() }, null, Modifier.size(36.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, fontSize = 18.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "ביטול")) } },
    )
}
