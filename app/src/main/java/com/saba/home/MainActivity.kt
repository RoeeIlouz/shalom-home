package com.saba.home

import android.Manifest
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import androidx.activity.result.IntentSenderRequest
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val level: Float) : VoiceState
    data object Thinking : VoiceState
    data class Confirm(val target: Target, val secondsLeft: Int) : VoiceState
    data class NotUnderstood(val transcript: String) : VoiceState
    data object NoModel : VoiceState
}

enum class Screen { HOME, ALL_APPS, PIN, ADMIN }

private const val REQ_CONFIGURE = 41

class MainActivity : ComponentActivity() {
    private lateinit var store: SettingsStore
    lateinit var brain: Brain
        private set

    var settings by mutableStateOf(Settings(emptyList()))
        private set
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var screen by mutableStateOf(Screen.HOME)
    var voice by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    /** Shown on the setup screen so the transcript and routing can be checked before handing the phone over. */
    var lastDebug by mutableStateOf("")
        private set

    /** Wrong PIN attempts. Every third miss locks the pad, for longer each time. */
    private var pinFailures = 0
    var pinLockedUntil by mutableStateOf(0L)
        private set

    fun tryPin(entered: String): Boolean {
        if (System.currentTimeMillis() < pinLockedUntil) return false
        if (entered == settings.pin) {
            pinFailures = 0
            screen = Screen.ADMIN
            return true
        }
        pinFailures++
        if (pinFailures % 3 == 0) pinLockedUntil = System.currentTimeMillis() + 60_000L * (pinFailures / 3)
        return false
    }

    private var voiceJob: Job? = null
    private var tts: TextToSpeech? = null

    lateinit var extrasHost: ExtrasHost
        private set

    /** Which home page is showing (0 = tiles, 1 = widgets and shortcuts). Home button resets it. */
    var homePage by mutableIntStateOf(0)

    // --- Adding a widget: allocate an id, get bind permission, run the widget's own setup, then save. ---
    private var addingWidgetId = 0

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) configureWidget(addingWidgetId) else abandonWidget()
    }
    private val configureWidgetResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) saveWidget(addingWidgetId) else abandonWidget()
    }

    fun addWidget(provider: AppWidgetProviderInfo) {
        val id = extrasHost.widgetHost.allocateAppWidgetId()
        addingWidgetId = id
        if (extrasHost.widgetManager.bindAppWidgetIdIfAllowed(id, provider.provider)) {
            configureWidget(id)
        } else {
            // The system asks the caregiver once whether this home screen may show widgets.
            bindWidget.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider))
        }
    }

    private fun configureWidget(id: Int) {
        val info = extrasHost.widgetInfo(id) ?: return abandonWidget()
        if (info.configure == null) return saveWidget(id)
        // e.g. the Contacts "Direct dial" widget asks which contact it should call.
        runCatching {
            configureWidgetResult.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(info.configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }.onFailure {
            // Some configure activities are not exported to other apps; the host API can still start them.
            runCatching { extrasHost.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null) }
                .onFailure { abandonWidget() }
        }
    }

    @Deprecated("Only used by AppWidgetHost.startAppWidgetConfigureActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
            if (resultCode == RESULT_OK) saveWidget(addingWidgetId) else abandonWidget()
        }
    }

    private fun saveWidget(id: Int) {
        val label = extrasHost.widgetInfo(id)?.loadLabel(packageManager) ?: "Widget"
        updateSettings(settings.copy(extras = settings.extras + Extra("w_$id", ExtraKind.WIDGET, label, widgetId = id)))
        addingWidgetId = 0
    }

    private fun abandonWidget() {
        if (addingWidgetId != 0) extrasHost.deleteWidget(addingWidgetId)
        addingWidgetId = 0
    }

    // --- Shortcut makers (e.g. Contacts "Direct dial"): the app asks its question, then hands back a pin request. ---
    private val shortcutMaker = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val data = res.data ?: return@registerForActivityResult
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(data) ?: return@registerForActivityResult
        // The caregiver chose this in setup, so it is approved straight away.
        extrasHost.accept(request, approved = true)?.let { updateSettings(settings.copy(extras = settings.extras + it)) }
    }

    private fun shortcutError(e: Throwable) = toast(
        if (e is SecurityException || !extrasHost.canUseShortcuts())
            tr("Make Shalom Home the default home app first (Setup > Make default home)",
                "קודם צריך להגדיר את Shalom Home כמסך הבית (הגדרות > הגדרה כמסך בית)")
        else tr("Could not add this shortcut", "לא הצלחתי להוסיף את הקיצור") + ": ${e.message}"
    )

    // Fallback when Android refuses the official route: open the app's shortcut screen ourselves.
    private val createShortcutDirect = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        extrasHost.fromCreateShortcutResult(data)
            .onSuccess { updateSettings(settings.copy(extras = settings.extras + it)) }
            .onFailure(::shortcutError)
    }

    fun runShortcutMaker(info: LauncherActivityInfo) {
        extrasHost.shortcutMakerIntent(info)
            .onSuccess { shortcutMaker.launch(IntentSenderRequest.Builder(it).build()) }
            .onFailure {
                runCatching {
                    createShortcutDirect.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(info.componentName))
                }.onFailure(::shortcutError)
            }
    }

    /** "home role / shortcut permission", refreshed on every resume, for the setup diagnostics line. */
    var shortcutStatus by mutableStateOf(Pair(false, false))
        private set

    fun addAppShortcut(info: ShortcutInfo) {
        extrasHost.pinAppShortcut(info, settings.extras)
            .onSuccess { updateSettings(settings.copy(extras = settings.extras + it)) }
            .onFailure(::shortcutError)
    }

    /** Last crash, saved by [installCrashRecorder] and shown in setup so it can be reported without a cable. */
    var lastCrash by mutableStateOf("")
        private set

    private fun installCrashRecorder() {
        val file = java.io.File(filesDir, "last_crash.txt")
        lastCrash = runCatching { file.readText() }.getOrDefault("")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText("${java.util.Date()} v$version\n" + Log.getStackTraceString(e).take(4000)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun clearCrash() {
        java.io.File(filesDir, "last_crash.txt").delete()
        lastCrash = ""
    }

    fun approveExtra(e: Extra) =
        updateSettings(settings.copy(extras = settings.extras.map { if (it.id == e.id) it.copy(approved = true) else it }))

    fun removeExtra(e: Extra) {
        when (e.kind) {
            ExtraKind.WIDGET -> extrasHost.deleteWidget(e.widgetId)
            ExtraKind.SHORTCUT -> extrasHost.unpin(e, settings.extras)
        }
        updateSettings(settings.copy(extras = settings.extras - e))
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        store = SettingsStore(this)
        brain = Brain(applicationContext)
        extrasHost = ExtrasHost(applicationContext)
        apps = Apps.installed(this)
        settings = store.load() ?: Settings(defaultTiles(apps)).also(store::save)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.forLanguageTag("he-IL")
        }
        lifecycleScope.launch { brain.warmUp(settings) }
        DebugHooks.handle(this, intent)

        setContent {
            // A launcher must never "go back" to anything; Back just returns to the main tiles.
            BackHandler { screen = Screen.HOME; cancelVoice() }
            SabaApp(this)
        }
    }

    override fun onStart() {
        super.onStart()
        extrasHost.widgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        runCatching { extrasHost.widgetHost.stopListening() }
    }

    override fun onResume() {
        super.onResume()
        apps = Apps.installed(this) // pick up apps installed or removed while we were away
        shortcutStatus = extrasHost.holdsHomeRole() to extrasHost.canUseShortcuts()
        // PinRequestActivity may have parked a shortcut for approval while we were in the background.
        store.load()?.let { if (it != settings) settings = it }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already home: reset to the tiles, never leave him in a sub-screen.
        if (screen != Screen.ADMIN) screen = Screen.HOME
        homePage = 0
        cancelVoice()
        DebugHooks.handle(this, intent)
    }

    fun updateSettings(new: Settings) {
        val modelChanged = new.whisperModel != settings.whisperModel || new.useLlm != settings.useLlm
        settings = new
        store.save(new)
        if (modelChanged) lifecycleScope.launch { brain.warmUp(new) }
    }

    /** Model download progress by file name. Lives here so leaving the setup screen does not cancel it. */
    val downloads = mutableStateMapOf<String, Float>()
    var modelsVersion by mutableIntStateOf(0)
        private set

    fun download(spec: ModelSpec) {
        if (spec.fileName in downloads) return
        downloads[spec.fileName] = 0f
        lifecycleScope.launch {
            runCatching { ModelCatalog.download(this@MainActivity, spec) { p -> downloads[spec.fileName] = p } }
                .onFailure { toast(tr("Download failed", "ההורדה נכשלה") + ": ${it.message}") }
            downloads.remove(spec.fileName)
            modelsVersion++
            brain.warmUp(settings)
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    fun contactFor(tile: Tile): Contact? = settings.contacts.firstOrNull { it.id == tile.target }

    fun open(target: Target) {
        when (target) {
            is Target.OfContact -> Apps.call(this, target.contact.number)
            is Target.OfApp -> Apps.launch(this, target.app.packageName)
            is Target.OfTile -> when (target.tile.kind) {
                TileKind.APP -> Apps.launch(this, target.tile.target)
                TileKind.CALL -> contactFor(target.tile)?.let { Apps.call(this, it.number) }
                TileKind.ALL_APPS -> screen = Screen.ALL_APPS
            }
        }
    }

    fun onTalkPressed() {
        if (voice is VoiceState.Listening || voice is VoiceState.Thinking) {
            cancelVoice()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else startListening()
    }

    /** Debug builds only (see [DebugHooks]): display a voice state for screenshots. Never starts a countdown or acts. */
    fun debugShowVoice(state: VoiceState) {
        voiceJob?.cancel()
        voice = state
    }

    fun cancelVoice() {
        voiceJob?.cancel()
        voiceJob = null
        voice = VoiceState.Idle
    }

    private fun startListening() {
        if (!ModelCatalog.isReady(this, ModelCatalog.whisperModels.firstOrNull { it.fileName == settings.whisperModel }
                ?: ModelCatalog.WHISPER_SMALL)) {
            voice = VoiceState.NoModel
            return
        }
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch {
            voice = VoiceState.Listening(0f)
            val audio = Recorder.recordUtterance { level -> voice = VoiceState.Listening(level) }
            if (audio.isEmpty()) {
                voice = VoiceState.NotUnderstood("")
                say("לא שמעתי. נסה שוב")
                return@launch
            }
            handleAudio(audio)
        }
    }

    /**
     * Test entry point for [DebugHooks]: runs recognition and logs the result, but never opens an app or
     * places a call. A test must not be able to dial anyone.
     */
    fun runOnAudio(audio: FloatArray) {
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch { handleAudio(audio, dryRun = true) }
    }

    /** Everything after the microphone: transcribe, resolve, confirm, act. */
    private suspend fun handleAudio(audio: FloatArray, dryRun: Boolean = false) {
        voice = VoiceState.Thinking
        val heard = brain.understand(audio, settings, apps)
        lastDebug = "\"${heard.transcript}\" -> ${heard.resolution.target?.spokenName ?: "nothing"} " +
            "(${heard.resolution.via}, ${heard.millis} ms)"
        Log.i("SabaHome", "RESULT $lastDebug")
        val target = heard.resolution.target
        if (dryRun) {
            voice = VoiceState.Idle
            return
        }
        if (target == null) {
            voice = VoiceState.NotUnderstood(heard.transcript)
            say("לא הבנתי. נסה שוב")
            return
        }
        // A short, cancellable countdown: a misheard name should never silently dial someone.
        say(describe(target))
        for (s in 3 downTo 1) {
            voice = VoiceState.Confirm(target, s)
            delay(1000)
        }
        voice = VoiceState.Idle
        open(target)
    }

    fun confirmNow() {
        val c = voice as? VoiceState.Confirm ?: return
        cancelVoice()
        open(c.target)
    }

    fun describe(target: Target): String = when {
        target is Target.OfContact -> "מתקשר ל${target.contact.name}"
        target is Target.OfTile && target.tile.kind == TileKind.CALL -> "מתקשר ל${target.tile.label}"
        target is Target.OfTile && target.tile.kind == TileKind.ALL_APPS -> "כל האפליקציות"
        else -> "פותח את ${target.spokenName}"
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saba")
    }

    override fun onDestroy() {
        tts?.shutdown()
        brain.close()
        super.onDestroy()
    }
}
