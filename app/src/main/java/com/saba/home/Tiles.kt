package com.saba.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class TileKind { APP, CALL, ALL_APPS }

// Each contact adds ~25 tokens to the LLM prompt; 30 fits easily in the 4096-token context below.
const val MAX_CONTACTS = 30

/**
 * Someone Grandpa can ask the assistant to call. [aliases] are the other ways he refers to them
 * ("הבת שלי", "ma fille", a nickname). A contact can be reachable by voice only, or also shown as a tile.
 */
data class Contact(
    val id: String,
    val name: String,
    val number: String,
    val aliases: List<String> = emptyList(),
)

/**
 * One big button on the home screen.
 * [aliases] are extra words Grandpa might say for it ("הבת שלי", "רחל"); they feed both
 * keyword matching and the language model's option list.
 */
data class Tile(
    val id: String,
    val kind: TileKind,
    val label: String,
    val target: String = "", // package name for APP, contact id for CALL
    val aliases: List<String> = emptyList(),
    val color: Long = 0xFF2E7D32,
)

enum class ExtraKind { WIDGET, SHORTCUT }

/**
 * Something hosted on the second page: another app's widget (e.g. Contacts "Direct dial") or a pinned
 * shortcut. Shortcut requests arrive from other apps unasked, so they wait with [approved] = false
 * until the caregiver approves them in setup.
 */
data class Extra(
    val id: String,
    val kind: ExtraKind,
    val label: String,
    val widgetId: Int = 0,
    val packageName: String = "",
    val shortcutId: String = "",
    /** Legacy shortcuts (INSTALL_SHORTCUT broadcast) carry a plain intent instead of a shortcut id. */
    val intentUri: String = "",
    val approved: Boolean = true,
)

data class Settings(
    val tiles: List<Tile>,
    val contacts: List<Contact> = emptyList(),
    val extras: List<Extra> = emptyList(),
    val pin: String = "0000",
    /** False until the caregiver replaces the default PIN; setup refuses to open further until then. */
    val pinChosen: Boolean = false,
    val language: String = "he", // "he", "fr" or "auto"
    val whisperModel: String = ModelCatalog.WHISPER_SMALL.fileName,
    val useLlm: Boolean = true,
    /** Show the phone's own wallpaper behind the tiles (a photo of the grandchildren, usually). */
    val showWallpaper: Boolean = true,
    /** Language of the setup screens: "auto" follows the phone, or "he" / "en". */
    val uiLanguage: String = "auto",
)

/** Persists the layout as JSON in app-private storage. Only the PIN-protected setup screen writes it. */
class SettingsStore(context: Context) {
    private val file = File(context.filesDir, "settings.json")

    fun load(): Settings? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val tiles = o.getJSONArray("tiles").let { arr ->
            (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                Tile(
                    id = t.getString("id"),
                    kind = TileKind.valueOf(t.getString("kind")),
                    label = t.getString("label"),
                    target = t.optString("target"),
                    aliases = t.optJSONArray("aliases")?.let { a -> (0 until a.length()).map(a::getString) }
                        ?: emptyList(),
                    color = t.optLong("color", 0xFF2E7D32),
                )
            }
        }
        val contacts = o.optJSONArray("contacts")?.let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Contact(
                    id = c.getString("id"),
                    name = c.getString("name"),
                    number = c.getString("number"),
                    aliases = c.optJSONArray("aliases")?.let { a -> (0 until a.length()).map(a::getString) }
                        ?: emptyList(),
                )
            }
        } ?: emptyList()
        val extras = o.optJSONArray("extras")?.let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                Extra(
                    id = e.getString("id"),
                    kind = ExtraKind.valueOf(e.getString("kind")),
                    label = e.optString("label"),
                    widgetId = e.optInt("widgetId"),
                    packageName = e.optString("packageName"),
                    shortcutId = e.optString("shortcutId"),
                    intentUri = e.optString("intentUri"),
                    approved = e.optBoolean("approved", true),
                )
            }
        } ?: emptyList()
        migrateCallTiles(Settings(
            tiles = tiles,
            contacts = contacts,
            extras = extras,
            pin = o.optString("pin", "0000"),
            pinChosen = o.optBoolean("pinChosen", false),
            language = o.optString("language", "he"),
            whisperModel = o.optString("whisperModel", ModelCatalog.WHISPER_SMALL.fileName),
            useLlm = o.optBoolean("useLlm", true),
            showWallpaper = o.optBoolean("showWallpaper", true),
            uiLanguage = o.optString("uiLanguage", "auto"),
        ))
    }.getOrNull()

    /** Older layouts stored the phone number on the tile itself; move it into a contact. */
    private fun migrateCallTiles(s: Settings): Settings {
        val ids = s.contacts.map { it.id }.toSet()
        val orphans = s.tiles.filter { it.kind == TileKind.CALL && it.target !in ids }
        if (orphans.isEmpty()) return s
        val moved = orphans.map { Contact("c_${it.id}", it.label, it.target, it.aliases) }
        return s.copy(
            contacts = s.contacts + moved,
            tiles = s.tiles.map { t -> if (t in orphans) t.copy(target = "c_${t.id}", aliases = emptyList()) else t },
        )
    }

    fun save(s: Settings) {
        val o = JSONObject()
            .put("pin", s.pin)
            .put("pinChosen", s.pinChosen)
            .put("extras", JSONArray().apply {
                s.extras.forEach { e ->
                    put(JSONObject()
                        .put("id", e.id)
                        .put("kind", e.kind.name)
                        .put("label", e.label)
                        .put("widgetId", e.widgetId)
                        .put("packageName", e.packageName)
                        .put("shortcutId", e.shortcutId)
                        .put("intentUri", e.intentUri)
                        .put("approved", e.approved))
                }
            })
            .put("contacts", JSONArray().apply {
                s.contacts.forEach { c ->
                    put(JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("number", c.number)
                        .put("aliases", JSONArray(c.aliases)))
                }
            })
            .put("language", s.language)
            .put("whisperModel", s.whisperModel)
            .put("useLlm", s.useLlm)
            .put("showWallpaper", s.showWallpaper)
            .put("uiLanguage", s.uiLanguage)
            .put("tiles", JSONArray().apply {
                s.tiles.forEach { t ->
                    put(JSONObject()
                        .put("id", t.id)
                        .put("kind", t.kind.name)
                        .put("label", t.label)
                        .put("target", t.target)
                        .put("aliases", JSONArray(t.aliases))
                        .put("color", t.color))
                }
            })
        val tmp = File(file.parentFile, "settings.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}

val TileColors = listOf(0xFF2E7D32, 0xFF1565C0, 0xFFC62828, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00838F, 0xFF4E342E)

/** Spoken names for common apps, so "תפתח וואטסאפ" works before anyone adds aliases. */
val KnownAppAliases = mapOf(
    "com.whatsapp" to listOf("וואטסאפ", "ווטסאפ", "וואצאפ", "ווצאפ", "וטסאפ", "whatsapp"),
    "com.google.android.youtube" to listOf("יוטיוב", "youtube"),
    "com.sec.android.app.camera" to listOf("מצלמה", "camera", "caméra"),
    "com.google.android.GoogleCamera" to listOf("מצלמה", "camera", "caméra"),
    "com.android.camera2" to listOf("מצלמה", "camera", "caméra"),
    "com.sec.android.gallery3d" to listOf("גלריה", "תמונות", "photos", "galerie"),
    "com.google.android.apps.photos" to listOf("גלריה", "תמונות", "photos", "galerie"),
    "com.samsung.android.dialer" to listOf("טלפון", "חייגן", "téléphone"),
    "com.google.android.dialer" to listOf("טלפון", "חייגן", "téléphone"),
    "com.samsung.android.messaging" to listOf("הודעות", "messages"),
    "com.google.android.apps.messaging" to listOf("הודעות", "messages"),
    "com.android.chrome" to listOf("אינטרנט", "כרום", "chrome"),
    "com.sec.android.app.sbrowser" to listOf("אינטרנט", "internet"),
    "com.google.android.calendar" to listOf("יומן", "calendrier"),
    "com.sec.android.app.clockpackage" to listOf("שעון", "שעון מעורר", "réveil"),
)

/** First-run layout: whichever of the usual suspects are installed, plus the full app list. */
fun defaultTiles(installed: List<AppInfo>): List<Tile> {
    val wanted = listOf(
        listOf("com.whatsapp") to "וואטסאפ",
        listOf("com.samsung.android.dialer", "com.google.android.dialer") to "טלפון",
        listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera") to "מצלמה",
        listOf("com.sec.android.gallery3d", "com.google.android.apps.photos") to "תמונות",
    )
    val pkgs = installed.map { it.packageName }.toSet()
    val tiles = wanted.mapNotNull { (candidates, label) ->
        candidates.firstOrNull { it in pkgs }?.let { pkg ->
            Tile(id = "app_${pkg.substringAfterLast('.')}", kind = TileKind.APP, label = label, target = pkg)
        }
    }.mapIndexed { i, t -> t.copy(color = TileColors[i % TileColors.size]) }
    return tiles + Tile(id = "all_apps", kind = TileKind.ALL_APPS, label = "כל האפליקציות", color = 0xFF455A64)
}
