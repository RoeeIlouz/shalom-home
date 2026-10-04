package com.saba.home

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import java.io.File

private const val TAG = "SabaExtras"
private const val HOST_ID = 0x5ABA

/** Widgets and pinned shortcuts hosted on the second page. */
class ExtrasHost(private val context: Context) {
    val widgetHost = AppWidgetHost(context, HOST_ID)
    val widgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun providers(): List<AppWidgetProviderInfo> =
        widgetManager.installedProviders.sortedBy { it.loadLabel(context.packageManager).lowercase() }

    fun widgetInfo(id: Int): AppWidgetProviderInfo? = widgetManager.getAppWidgetInfo(id)

    fun deleteWidget(id: Int) = runCatching { widgetHost.deleteAppWidgetId(id) }

    /** Only the default home app may read or launch pinned shortcuts (as far as Android's own check is concerned). */
    fun canUseShortcuts(): Boolean = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    /** Whether Android's role manager lists this app as the home app. Shown in setup to diagnose OEM differences. */
    fun holdsHomeRole(): Boolean = runCatching {
        context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    /**
     * Result of running a shortcut maker directly (ACTION_CREATE_SHORTCUT). Modern apps hand back a pin request;
     * older ones hand back a plain intent plus a name and icon, which needs no special permission at all.
     */
    fun fromCreateShortcutResult(data: Intent): Result<Extra> = runCatching {
        launcherApps.getPinItemRequest(data)?.let { req ->
            return@runCatching accept(req, approved = true) ?: error("the app's pin request was refused")
        }
        @Suppress("DEPRECATION")
        val target = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: error("the app returned no shortcut")
        @Suppress("DEPRECATION")
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        val id = "x_${System.currentTimeMillis()}"
        @Suppress("DEPRECATION")
        val icon: Drawable? = data.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON)
            ?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
            ?: data.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)?.let { res ->
                runCatching {
                    val r = context.packageManager.getResourcesForApplication(res.packageName)
                    r.getDrawable(r.getIdentifier(res.resourceName, null, null), null)
                }.getOrNull()
            }
        saveIcon(id, icon)
        Extra(id, ExtraKind.SHORTCUT, name, packageName = target.`package` ?: target.component?.packageName ?: "",
            intentUri = target.toUri(0), approved = true)
    }.onFailure { Log.w(TAG, "create-shortcut result unusable", it) }

    private fun shortcut(e: Extra): ShortcutInfo? = runCatching {
        val q = LauncherApps.ShortcutQuery()
            .setPackage(e.packageName)
            .setShortcutIds(listOf(e.shortcutId))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        launcherApps.getShortcuts(q, Process.myUserHandle())?.firstOrNull()
    }.getOrNull()

    fun launch(e: Extra): Boolean = runCatching {
        if (e.intentUri.isNotEmpty()) {
            context.startActivity(Intent.parseUri(e.intentUri, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        launcherApps.startShortcut(e.packageName, e.shortcutId, null, null, Process.myUserHandle())
        true
    }.getOrElse {
        Log.w(TAG, "shortcut ${e.shortcutId} failed", it)
        false
    }

    /** Icon saved when the shortcut was pinned, so it still draws if the source app is slow to answer. */
    fun icon(e: Extra): Bitmap? = iconFile(context, e.id).takeIf { it.exists() }
        ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /**
     * Unpins a rejected or removed shortcut. Pinning is per launcher, so we re-pin every other
     * shortcut we still keep from the same app.
     */
    fun unpin(e: Extra, all: List<Extra>) = runCatching {
        if (e.intentUri.isNotEmpty()) {
            iconFile(context, e.id).delete()
            return@runCatching
        }
        val keep = all.filter { it.kind == ExtraKind.SHORTCUT && it.packageName == e.packageName && it.id != e.id }
            .map { it.shortcutId }
        launcherApps.pinShortcuts(e.packageName, keep, Process.myUserHandle())
        iconFile(context, e.id).delete()
    }

    /** Apps that offer "shortcut makers", e.g. Contacts "Direct dial", which ask a question and then pin a shortcut. */
    fun shortcutMakers(): List<LauncherActivityInfo> = runCatching {
        launcherApps.getShortcutConfigActivityList(null, Process.myUserHandle())
    }.getOrDefault(emptyList()).sortedBy { it.label.toString().lowercase() }

    /** Fails with a SecurityException unless this app is the default home app. */
    fun shortcutMakerIntent(info: LauncherActivityInfo): Result<IntentSender> =
        runCatching { launcherApps.getShortcutConfigActivityIntent(info) ?: error("no intent from ${info.componentName}") }
            .onFailure { Log.w(TAG, "shortcut maker failed", it) }

    /** Shortcuts apps publish for their launcher icon's long-press menu ("New message", "Navigate home"...). */
    fun appShortcuts(packageName: String): List<ShortcutInfo> = runCatching {
        val q = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        launcherApps.getShortcuts(q, Process.myUserHandle()).orEmpty()
    }.getOrDefault(emptyList())

    fun shortcutIcon(info: ShortcutInfo): Drawable? =
        runCatching { launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) }.getOrNull()

    /** Pins an app shortcut the caregiver picked in setup. Pinning is per launcher, so keep the others from that app. */
    fun pinAppShortcut(info: ShortcutInfo, all: List<Extra>): Result<Extra> = runCatching {
        val keep = all.filter { it.kind == ExtraKind.SHORTCUT && it.packageName == info.`package` && it.intentUri.isEmpty() }
            .map { it.shortcutId }
        launcherApps.pinShortcuts(info.`package`, keep + info.id, Process.myUserHandle())
        val id = "x_${System.currentTimeMillis()}"
        saveIcon(id, shortcutIcon(info))
        Extra(id, ExtraKind.SHORTCUT, (info.shortLabel ?: info.longLabel ?: "").toString(),
            packageName = info.`package`, shortcutId = info.id, approved = true)
    }.onFailure { Log.w(TAG, "pin shortcut failed", it) }

    /** Accepts a pin request (from another app, or from a shortcut maker run in setup) and describes it. */
    fun accept(request: LauncherApps.PinItemRequest, approved: Boolean): Extra? {
        if (!request.isValid) return null
        val id = "x_${System.currentTimeMillis()}"
        return when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val info = request.shortcutInfo ?: return null
                if (!request.accept()) return null
                saveIcon(id, shortcutIcon(info))
                Extra(id, ExtraKind.SHORTCUT, (info.shortLabel ?: info.longLabel ?: "").toString(),
                    packageName = info.`package`, shortcutId = info.id, approved = approved)
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val widgetId = widgetHost.allocateAppWidgetId()
                if (!request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) })) {
                    deleteWidget(widgetId)
                    return null
                }
                val label = request.getAppWidgetProviderInfo(context)?.loadLabel(context.packageManager) ?: "Widget"
                Extra(id, ExtraKind.WIDGET, label, widgetId = widgetId, approved = approved)
            }
            else -> null
        }
    }

    private fun saveIcon(id: String, d: Drawable?) = runCatching {
        d?.toBitmap(192, 192)?.let { bmp ->
            iconFile(context, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        fun iconFile(context: Context, id: String) = File(File(context.filesDir, "icons").apply { mkdirs() }, "$id.png")
    }
}

/**
 * Receives "Add to Home screen" requests from other apps (Contacts, Chrome...). They are accepted so the
 * shortcut stays valid, but parked as unapproved: nothing appears on Grandpa's screen until the caregiver
 * approves it in setup.
 */
class PinRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request == null || !request.isValid) {
            finish()
            return
        }
        val store = SettingsStore(this)
        val settings = store.load()
        if (settings == null) {
            finish()
            return
        }
        val extra = ExtrasHost(this).accept(request, approved = false) ?: return finish()
        store.save(settings.copy(extras = settings.extras + extra))
        Toast.makeText(this, "נשמר. יופיע אחרי אישור בהגדרות", Toast.LENGTH_LONG).show() // shown to Grandpa: Hebrew
        finish()
    }
}

/**
 * The pre-Android-8 way of adding a home screen shortcut, still used by some contacts apps.
 * Parked for approval exactly like [PinRequestActivity].
 */
class InstallShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: return
        @Suppress("DEPRECATION")
        val name = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: "Shortcut"
        val store = SettingsStore(context)
        val settings = store.load() ?: return
        val uri = target.toUri(0)
        if (settings.extras.any { it.intentUri == uri }) return
        val id = "x_${System.currentTimeMillis()}"
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON))?.let { bmp ->
            runCatching { ExtrasHost.iconFile(context, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        store.save(settings.copy(extras = settings.extras +
            Extra(id, ExtraKind.SHORTCUT, name, packageName = target.`package` ?: target.component?.packageName ?: "",
                intentUri = uri, approved = false)))
        Log.i(TAG, "parked legacy shortcut $name for approval")
    }
}
