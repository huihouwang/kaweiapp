package com.summer.shotguide

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.Configuration
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    private enum class ReferenceMode(val title: String, val shortTitle: String) {
        LINES("Line guide", "Lines"),
        ORIGINAL("Original photo", "Original"),
        OFF("Hide reference", "Ref. off"),
    }

    private val ink = Color.rgb(35, 45, 43)
    private val paper = Color.rgb(251, 248, 243)
    private val accent = Color.rgb(255, 91, 72)
    private val catalog by lazy { ShotCatalog.load(this) }
    private val shots get() = catalog.shots
    private val growthPreferences by lazy { getSharedPreferences(GROWTH_PREFERENCES, MODE_PRIVATE) }
    private fun unlockedScenes(): Set<String> = growthPreferences.getStringSet("unlocked_scenes", emptySet()).orEmpty().toSet()
    private fun locked(shot: Shot) = isShareLocked(shot, unlockedScenes())
    private var filter = ShotFilter()
    private var homeScroll = 0
    private var gridEnabled = true
    private var customReference = false
    private val referenceModel by lazy { ViewModelProvider(this)[ReferenceViewModel::class.java] }
    private var referenceMode = ReferenceMode.LINES
    private var lastVisibleReference = ReferenceMode.LINES
    private var lineOpacity = 45
    private var photoOpacity = 30
    private var whiteLines = true
    private val photos = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val lines = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private fun photoFor(shot: Shot): Bitmap = photos.get(shot.id) ?: assets.open(shot.photo).use { BitmapFactory.decodeStream(it) }!!.also { photos.put(shot.id, it) }
    private val templatePhoto get() = photoFor(selected)
    private val templateLines get(): Bitmap = selected.overlay?.let { path -> lines.get(selected.id) ?: assets.open(path).use { BitmapFactory.decodeStream(it) }!!.also { lines.put(selected.id, it) } } ?: templatePhoto
    private var zoomRatio = 1f
    private var exposureIndex = 0
    private var timerSeconds = 0
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var mirrorFrontCapture = true
    private var captureFlash = false
    private val handler = Handler(Looper.getMainLooper())
    private var countdown: Runnable? = null
    private var countdownLabel: TextView? = null
    private var controlsReady: (() -> Unit)? = null
    private var exposureRequest = 0
    private var zoomRequest = 0
    private var revealLive: (() -> Unit)? = null
    private var referenceStatusBanner: LinearLayout? = null
    private var referenceStatusText: TextView? = null
    private var referenceStatusProgress: ProgressBar? = null
    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun activeReference(): Bitmap = if (customReference && referenceFile.exists()) loadBitmap(referenceFile, 1600) else templatePhoto
    private lateinit var selected: Shot
    private var screen = "home"
    private var busy = false
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var lastFile: File? = null
    private var savedUri: Uri? = null
    private var generation = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val referenceFile get() = File(filesDir, "reference.jpg")
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) showCamera() else { showHome(); message("Camera permission is required. Tap a shot again to retry, or enable permission in system settings.") }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
    private val pickReference = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            referenceModel.importPhoto(uri)
            showCustomReference()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        selected = shots.find { it.id == savedInstanceState?.getString("shot") } ?: shots.first()
        filter = ShotFilter(savedInstanceState?.getString("filterScene"), savedInstanceState?.getString("filterPose"), savedInstanceState?.getString("filterOrientation"))
        homeScroll = savedInstanceState?.getInt("homeScroll", 0) ?: 0
        gridEnabled = savedInstanceState?.getBoolean("grid", true) ?: true
        val preferences = getPreferences(MODE_PRIVATE)
        val savedMode = savedInstanceState?.getString("referenceMode") ?: preferences.getString("reference_mode", null)
        referenceMode = ReferenceMode.entries.firstOrNull { it.name == savedMode } ?: ReferenceMode.LINES
        lastVisibleReference = if (referenceMode != ReferenceMode.OFF) referenceMode else {
            if (preferences.getString("last_visible_reference", null) == ReferenceMode.ORIGINAL.name) ReferenceMode.ORIGINAL else ReferenceMode.LINES
        }
        lineOpacity = preferences.getInt("line_opacity", 45)
        photoOpacity = preferences.getInt("photo_opacity", 30)
        whiteLines = preferences.getBoolean("white_lines", true)
        customReference = savedInstanceState?.getBoolean("customReference", false) ?: false
        zoomRatio = savedInstanceState?.getFloat("zoom", 1f) ?: 1f
        exposureIndex = savedInstanceState?.getInt("exposure", 0) ?: 0
        timerSeconds = savedInstanceState?.getInt("timer", 0) ?: 0
        lensFacing = savedInstanceState?.getInt("lensFacing", CameraSelector.LENS_FACING_BACK) ?: CameraSelector.LENS_FACING_BACK
        mirrorFrontCapture = savedInstanceState?.getBoolean("mirrorFrontCapture", true) ?: true
        captureFlash = savedInstanceState?.getBoolean("captureFlash") ?: false
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) { message("Processing photo, please wait"); return }
                when (screen) { "camera" -> if (customReference) showCustomReference() else showShot(); "reference", "shot" -> showHome(); "result" -> showCamera(); "onboarding" -> finish(); else -> finish() }
            }
        })
        val restored = savedInstanceState?.getString("lastFile")?.let(::File)
        if (savedInstanceState?.getString("screen") == "result" && restored?.exists() == true) {
            lastFile = restored
            savedUri = savedInstanceState.getString("savedUri")?.let(Uri::parse)
            showResult()
        } else if (savedInstanceState?.getString("screen") == "reference") showCustomReference()
        else if (savedInstanceState?.getString("screen") == "camera") startCamera()
        else if (savedInstanceState?.getString("screen") == "shot") showShot()
        else if (!growthPreferences.getBoolean("onboarding_seen", false)) showOnboarding()
        else showHome()
        referenceModel.state.observe(this) { state ->
            if (screen == "reference") showCustomReference()
            else {
                updateReferenceStatus(state)
                if (!state.working && state.completed && referenceModel.consumeCompletionAnnouncement()) {
                    if (screen == "camera" && customReference) message("Line guide ready · choose it in Reference")
                    else if (screen == "home") message("Line guide complete · tap the status bar to view")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filterScene", filter.scene); outState.putString("filterPose", filter.pose); outState.putString("filterOrientation", filter.orientation)
        outState.putInt("homeScroll", homeScroll); outState.putBoolean("grid", gridEnabled)
        outState.putString("shot", selected.id); outState.putString("screen", screen)
        outState.putBoolean("customReference", customReference); outState.putString("lastFile", lastFile?.absolutePath)
        outState.putString("referenceMode", referenceMode.name)
        outState.putString("savedUri", savedUri?.toString())
        outState.putFloat("zoom", zoomRatio); outState.putInt("exposure", exposureIndex); outState.putInt("timer", timerSeconds)
        outState.putInt("lensFacing", lensFacing); outState.putBoolean("mirrorFrontCapture", mirrorFrontCapture)
        outState.putBoolean("captureFlash", captureFlash)
    }

    override fun onResume() {
        super.onResume()
        val rewardedScene = growthPreferences.getString("last_share_reward_scene", null) ?: return
        val rewardedShot = growthPreferences.getString("last_share_reward_shot", "").orEmpty()
        growthPreferences.edit().remove("last_share_reward_scene").remove("last_share_reward_shot").apply()
        if (!SHARE_GATING_ENABLED) return
        event("share_target_selected_${rewardedScene}_${rewardedShot}")
        message("All ${sceneTitle(rewardedScene)} shots are now unlocked")
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun tr(value: String) = AppLanguage.text(this, value)
    private fun label(value: String, size: Float = 16f, color: Int = ink) = TextView(this).apply {
        text = tr(value); textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = tr(value); isAllCaps = false; textSize = 15f; minHeight = dp(48)
        setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary) accent else Color.rgb(233, 230, 220), 14)
        setOnClickListener { if (!busy) action() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun space(root: LinearLayout, height: Int = 12) { root.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun root(dark: Boolean = false): LinearLayout {
        val root = column().apply { setBackgroundColor(if (dark) Color.rgb(18, 24, 23) else paper) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
        setContentView(root)
        return root
    }
    private fun message(value: String) { if (!isDestroyed) Toast.makeText(this, tr(value), Toast.LENGTH_LONG).show() }
    private fun event(name: String) {
        if (worker.isShutdown) return
        val entry = "${System.currentTimeMillis()},${selected.id},$name\n"
        worker.execute { runCatching { File(filesDir, "trial.csv").appendText(entry) } }
    }

    private fun showHome() {
        generation++; provider?.unbindAll(); capture = null; screen = "home"
        val outer = root()
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(8),dp(14),dp(8)); background = rounded(Color.rgb(237,234,227),14)
            isClickable = true; setOnClickListener { referenceModel.markCompletionSeen(); showCustomReference() }
        }
        val statusText = label("",13f).apply { typeface = Typeface.DEFAULT_BOLD }
        val statusProgress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        status.addView(statusText,LinearLayout.LayoutParams(-1,-2))
        status.addView(statusProgress,LinearLayout.LayoutParams(-1,dp(5)).apply { topMargin = dp(5) })
        referenceStatusBanner = status; referenceStatusText = statusText; referenceStatusProgress = statusProgress
        outer.addView(status,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(12),dp(6),dp(12),0) })
        val discovery = DiscoveryView(this, catalog, filter, homeScroll,
            shotPhoto = { photoFor(it) },
            isLocked = ::locked,
            showStarter = !growthPreferences.getBoolean("first_capture_completed", false),
            onStarter = ::startFirstShot,
            onFilter = { filter = it; homeScroll = 0; event("filter_changed") },
            onSelect = { shot, position ->
                homeScroll = position; customReference = false; selected = shot
                event("template_open"); showShot()
            },
            onLocked = ::lockedTemplateDialog,
            onRecent = {
                val id = getPreferences(MODE_PRIVATE).getString("recent", null)
                val shot = shots.find { it.id == id }
                if (shot != null && locked(shot)) lockedTemplateDialog(shot)
                else if (shot != null) { selected = shot; customReference = false; showShot() }
                else message("After shooting, your most recent photo will appear here")
            },
            onImport = { if (referenceModel.state.value?.working == true) showCustomReference() else pickReference.launch("image/*") },
            onCustom = if (referenceFile.exists() || referenceModel.state.value?.working == true) ({ showCustomReference() }) else null,
            onLanguage = ::languageDialog,
            onFeedback = ::feedbackDialog)
        outer.addView(discovery, LinearLayout.LayoutParams(-1, 0, 1f))
        updateReferenceStatus(referenceModel.state.value ?: ReferenceState())
    }

    private fun updateReferenceStatus(state: ReferenceState) {
        val banner = referenceStatusBanner ?: return
        val failed = !state.working && !state.completed && state.message.isNotBlank()
        banner.visibility = if (state.working || referenceModel.shouldShowCompleted() || failed) View.VISIBLE else View.GONE
        when {
            state.working -> {
                referenceStatusText?.text = tr("Generating line guide") + " ${state.progress}% · " + tr(state.message)
                referenceStatusProgress?.visibility = View.VISIBLE
                referenceStatusProgress?.progress = state.progress
            }
            referenceModel.shouldShowCompleted() -> {
                referenceStatusText?.text = tr("Line guide complete · tap to view")
                referenceStatusProgress?.visibility = View.VISIBLE
                referenceStatusProgress?.progress = 100
            }
            failed -> {
                referenceStatusText?.text = tr("Line guide failed · tap to retry")
                referenceStatusProgress?.visibility = View.GONE
            }
        }
    }

    private fun languageDialog() {
        val values = arrayOf(AppLanguage.SYSTEM, AppLanguage.CHINESE, AppLanguage.ENGLISH)
        val labels = arrayOf(tr("Follow system"), tr("Simplified Chinese"), "English")
        val selectedIndex = values.indexOf(AppLanguage.saved(this)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(tr("Language")).setSingleChoiceItems(labels, selectedIndex) { dialog, index ->
            if (values[index] != AppLanguage.saved(this)) {
                AppLanguage.set(this, values[index])
                dialog.dismiss()
                recreate()
            } else dialog.dismiss()
        }.setNegativeButton(tr("Cancel"), null).show()
    }

    private fun showCustomReference() {
        generation++; provider?.unbindAll(); capture = null; screen = "reference"
        if (referenceModel.generateMissingOnce()) { showCustomReference(); return }
        val state = referenceModel.state.value ?: ReferenceState()
        if (state.completed) referenceModel.markCompletionSeen()
        val outer = root()
        outer.addView(button("‹ Back") { showHome() })
        outer.addView(label("Your reference photo", 24f).apply { setPadding(dp(14),dp(12),dp(14),dp(8)) })
        val media = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
        val hasPhoto = !state.readingPhoto && referenceFile.exists()
        val hasLines = !state.working && referenceModel.hasValidLines()
        val source = if (hasLines) referenceModel.linesFile else referenceFile
        if (hasPhoto) media.setImageBitmap(BitmapFactory.decodeFile(source.path))
        outer.addView(media,LinearLayout.LayoutParams(-1,0,1f))
        outer.addView(label(state.message.ifBlank { if (hasLines) "Saved on this device" else "Generate a guide from your saved photo" },14f).apply { setPadding(dp(14),dp(8),dp(14),dp(8)) })
        if (state.working) {
            outer.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = state.progress },LinearLayout.LayoutParams(-1,dp(12)).apply { setMargins(dp(14),0,dp(14),0) })
            outer.addView(label("${state.progress}%",13f).apply { gravity = Gravity.CENTER })
            outer.addView(label("You can go back while this finishes.",13f).apply { gravity = Gravity.CENTER })
        } else {
            if (hasLines) {
                var originalShown = false
                outer.addView(button("Compare with original") {
                    originalShown = !originalShown
                    media.setImageBitmap(BitmapFactory.decodeFile((if (originalShown) referenceFile else referenceModel.linesFile).path))
                })
                outer.addView(button("Shoot with this guide",true) { shootWithCustomReference(ReferenceMode.LINES) })
            }
            if (hasPhoto) {
                outer.addView(button(if (hasLines) "Regenerate guide" else "Generate / retry") { referenceModel.regenerate() })
            }
        }
        if (hasPhoto) {
            outer.addView(button("Shoot with original", !hasLines) { shootWithCustomReference(ReferenceMode.ORIGINAL) })
        }
        if (!state.working) {
            outer.addView(button("Choose another photo") { pickReference.launch("image/*") })
        }
    }

    private fun shootWithCustomReference(mode: ReferenceMode) {
        customReference = true
        selectReferenceMode(mode)
        startCamera()
    }

    private fun selectReferenceMode(mode: ReferenceMode) {
        if (mode != ReferenceMode.OFF) lastVisibleReference = mode
        referenceMode = mode
        getPreferences(MODE_PRIVATE).edit()
            .putString("reference_mode", mode.name)
            .putString("last_visible_reference", lastVisibleReference.name)
            .apply()
    }

    private fun startFirstShot() {
        selected = shots.firstOrNull { !locked(it) } ?: shots.first()
        customReference = false
        event("onboarding_start")
        showShot()
    }

    private fun showOnboarding() {
        generation++; provider?.unbindAll(); capture = null; screen = "onboarding"
        val outer = root()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = column().apply { setPadding(dp(22), dp(20), dp(22), dp(20)) }
        scroll.addView(content)
        content.addView(label("SHOTGUIDE · YOUR FIRST SHOT", 14f, accent).apply { typeface = Typeface.DEFAULT_BOLD })
        content.addView(label("Take a better photo\non your first try", 36f).apply {
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(label("Choose a reference, align the position and press the shutter. No photography lessons or perfect match required.", 16f, Color.rgb(94, 99, 95)))
        space(content, 18)
        val hero = FrameLayout(this).apply { background = rounded(Color.rgb(231, 226, 217), 24); clipToOutline = true }
        hero.addView(GridReferenceView(photoFor(shots.first()), true), FrameLayout.LayoutParams(-1, -1))
        hero.addView(label("Finish your first shot in about 1 minute", 14f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(0xD92B2B29.toInt(), 20)
        }, FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(14), 0, 0, dp(14)) })
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(if (landscape) 240 else 330)))
        space(content, 18)
        listOf(
            Triple("01", "Choose a look", "Start with any template that feels easy"),
            Triple("02", "Align with the guide", "Move the phone to match subject position and scale"),
            Triple("03", "Share only if you want", "Your original saves normally; the shot card is optional")
        ).forEach { (number, title, description) ->
            val row = LinearLayout(this).apply { gravity = Gravity.TOP; setPadding(0, dp(7), 0, dp(7)) }
            row.addView(label(number, 15f, accent).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; background = rounded(Color.rgb(255, 228, 222), 18) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val copy = column().apply { setPadding(dp(13), 0, 0, 0) }
            copy.addView(label(title, 17f).apply { typeface = Typeface.DEFAULT_BOLD })
            copy.addView(label(description, 13f, Color.rgb(102, 106, 102)))
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(row)
        }
        space(content, 12)
        val footer = column().apply { setPadding(dp(22), dp(8), dp(22), dp(12)); setBackgroundColor(paper) }
        footer.addView(button("Take my first shot", true) {
            growthPreferences.edit().putBoolean("onboarding_seen", true).apply()
            startFirstShot()
        })
        space(footer, 6)
        footer.addView(button("Browse all templates") {
            growthPreferences.edit().putBoolean("onboarding_seen", true).apply()
            event("onboarding_browse")
            showHome()
        })
        outer.addView(footer, LinearLayout.LayoutParams(-1, -2))
    }

    private fun sceneTitle(id: String): String = catalog.scenes.find { it.id == id }?.title ?: id

    private fun lockedTemplateDialog(shot: Shot) {
        val scene = rewardScene(shot, filter.scene)
        val title = scene?.let(::sceneTitle) ?: "this scene"
        AlertDialog.Builder(this)
            .setTitle(tr("Share once to unlock $title"))
            .setMessage(if (AppLanguage.effective(this) == AppLanguage.CHINESE) "先使用任意免费模板。完成拍摄后分享自己的拍法卡，即可解锁$title 的全部拍法。\n\n保存照片和基础拍摄功能始终不受限制。" else "Use any free template first. Share your own shot card from the result page to unlock every shot in $title.\n\nSaving photos and basic shooting are never restricted.")
            .setPositiveButton(tr("Use a free template")) { _, _ ->
                val free = shots.firstOrNull { candidate ->
                    !locked(candidate) && (scene == null || scene in candidate.scenes)
                } ?: shots.first { !locked(it) }
                selected = free; customReference = false; event("locked_to_free_${shot.id}"); showShot()
            }
            .setNegativeButton(if (AppLanguage.effective(this) == AppLanguage.CHINESE) "暂不" else "Not now", null)
            .show()
        event("share_lock_seen_${shot.id}")
    }

    private fun showShot() {
        generation++; provider?.unbindAll(); capture = null; screen = "shot"
        val outer = root(true)
        val heading = LinearLayout(this).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); gravity = Gravity.CENTER_VERTICAL }
        heading.addView(button("←") { showHome() }, LinearLayout.LayoutParams(dp(52), dp(44)))
        val viewToggle = button("Guide") {}
        heading.addView(label(selected.title, 16f, Color.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(viewToggle, LinearLayout.LayoutParams(dp(82), dp(44)))
        outer.addView(heading)
        val media = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12,12,11)) }
        val image = ImageView(this).apply { setImageBitmap(templatePhoto); scaleType = ImageView.ScaleType.FIT_CENTER }
        media.addView(image, FrameLayout.LayoutParams(-1, -1))
        var showingLines = false
        viewToggle.setOnClickListener {
            showingLines = !showingLines
            image.setImageBitmap(if(showingLines) templateLines else templatePhoto)
            image.colorFilter = if(showingLines) PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) else null
            viewToggle.text = tr(if(showingLines) "Photo" else "Guide")
        }
        val titleOverlay = column().apply { setPadding(dp(18), dp(10), dp(18), dp(16)) }
        titleOverlay.addView(label(selected.subtitle, 11f, Color.BLACK).apply { setPadding(dp(8),dp(4),dp(8),dp(4)); background = rounded(Color.rgb(223,255,114),20) }, LinearLayout.LayoutParams(-2,-2))
        titleOverlay.addView(label(selected.title, 27f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0,dp(6),0,0) })
        media.addView(titleOverlay, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        outer.addView(media, LinearLayout.LayoutParams(-1, 0, 1f))

        val sheetScroll = ScrollView(this).apply { background = rounded(Color.WHITE, 28); isVerticalScrollBarEnabled = false }
        val sheet = column().apply { setPadding(dp(17), dp(15), dp(17), dp(18)) }
        sheetScroll.addView(sheet)
        sheet.addView(View(this).apply { background = rounded(Color.rgb(222,218,213),5) }, LinearLayout.LayoutParams(dp(36),dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(13) })
        val lens = Regex("(?:0\\.5|0\\.6|1|2)×").find(selected.position)?.value ?: "1×"
        val pose = when(selected.pose) { "stand" -> "Standing"; "sit" -> "Sitting"; "walk" -> "Walking"; "lie" -> "Lying"; else -> "Natural" }
        val direction = if(selected.orientation == "landscape") "Landscape" else "Portrait"
        val meta = LinearLayout(this)
        listOf(lens to "Lens", pose to "Pose", direction to "Frame", "30 sec" to "Est. time").forEach { (value, caption) ->
            val card = column().apply { gravity = Gravity.CENTER; setPadding(dp(4),dp(8),dp(4),dp(8)); background = rounded(Color.rgb(244,242,238),12) }
            card.addView(label(value,12f).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
            card.addView(label(caption,9f,Color.GRAY).apply { gravity = Gravity.CENTER })
            meta.addView(card, LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd = dp(5) })
        }
        sheet.addView(meta)
        sheet.addView(label("Follow these three steps",14f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2),dp(13),0,dp(5)) })
        listOf(selected.action, selected.position, selected.light).forEachIndexed { index, text ->
            sheet.addView(label("${index+1}. $text",12f,Color.rgb(103,97,89)).apply { setPadding(dp(3),dp(2),dp(3),dp(2)) })
        }
        sheet.addView(button("Start camera", true) { startCamera() }, LinearLayout.LayoutParams(-1,dp(54)).apply { topMargin = dp(12) })
        outer.addView(sheetScroll, LinearLayout.LayoutParams(-1, dp(if(landscape) 260 else 335)))
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun showCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { startCamera(); return }
        if (customReference && !referenceFile.isFile) {
            showCustomReference()
            return
        }
        generation++; val token = generation
        if (!customReference) getPreferences(MODE_PRIVATE).edit().putString("recent", selected.id).apply()
        screen = "camera"; capture = null; camera = null; provider?.unbindAll()
        val outer = root(true)
        val reference = activeReference()
        val guide = PoseOverlay(reference)
        if (referenceMode == ReferenceMode.LINES && !guide.loadLines()) {
            referenceMode = ReferenceMode.ORIGINAL
            lastVisibleReference = ReferenceMode.ORIGINAL
            if (customReference) referenceModel.generateMissingOnce()
        }
        guide.refresh()
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        heading.addView(button("×") { if (customReference) showCustomReference() else showShot() }, LinearLayout.LayoutParams(dp(52), dp(44)))
        heading.addView(label(if (customReference) "My reference" else selected.title, 15f, Color.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        val referenceButton = button(referenceMode.shortTitle) {}
        heading.addView(referenceButton, LinearLayout.LayoutParams(dp(104), dp(44)))
        outer.addView(heading)
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val stage = FrameLayout(this)
        body.addView(stage, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val frame = FrameLayout(this)
        stage.addView(frame, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = tr("Tap the preview to focus")
        }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        frame.addView(guide, FrameLayout.LayoutParams(-1, -1))
        var restoreGuide: Runnable? = null
        revealLive = {
            restoreGuide?.let { guide.removeCallbacks(it) }
            guide.visibility = View.INVISIBLE
            val restore = Runnable { if (token == generation) guide.refresh() }
            restoreGuide = restore
            guide.postDelayed(restore, 1200)
        }
        val grid = GridLayer(reference.width, reference.height).apply { visibility = if(gridEnabled) View.VISIBLE else View.INVISIBLE }
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        val status = label("Opening ${lensFacingName(lensFacing)} camera…", 14f, Color.WHITE).apply { gravity = Gravity.CENTER; setBackgroundColor(0x88000000.toInt()); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        stage.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val countdownText = label("", 60f, Color.WHITE).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 2f, Color.BLACK); visibility = View.GONE }
        countdownLabel = countdownText
        frame.addView(countdownText, FrameLayout.LayoutParams(-1, -1))
        val bottom = column().apply { setPadding(dp(12), dp(2), dp(12), dp(8)) }
        val panel = ScrollView(this).apply { isFillViewport = false }
        panel.addView(bottom)
        body.addView(panel, if (landscape) LinearLayout.LayoutParams(dp(220), -1) else LinearLayout.LayoutParams(-1, -2))

        val referenceRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val thumbnail = ImageButton(this).apply {
            setImageBitmap(reference)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            background = rounded(paper, 8)
            clipToOutline = true
            contentDescription = tr("View full photo")
            setOnClickListener { if (!busy) referenceDialog(reference) }
        }
        referenceRow.addView(thumbnail, LinearLayout.LayoutParams(dp(36), dp(36)))
        val opacity = SeekBar(this).apply {
            max = 100
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            contentDescription = tr("Reference opacity")
        }
        referenceRow.addView(opacity, LinearLayout.LayoutParams(0, -1, 1f))
        val opacityValue = label("", 12f, Color.WHITE).apply { gravity = Gravity.CENTER }
        referenceRow.addView(opacityValue, LinearLayout.LayoutParams(dp(38), -2))
        val toggleReference = button("Hide") {}.apply { setPadding(dp(4), 0, dp(4), 0) }
        referenceRow.addView(toggleReference, LinearLayout.LayoutParams(dp(62), dp(38)))
        bottom.addView(referenceRow, LinearLayout.LayoutParams(-1, dp(44)))

        fun updateReferenceControls() {
            val visible = referenceMode != ReferenceMode.OFF
            referenceButton.text = tr(referenceMode.shortTitle)
            referenceButton.contentDescription = "${tr("Reference mode")} · ${tr(referenceMode.title)}"
            toggleReference.text = tr(if (visible) "Hide" else "Show")
            toggleReference.contentDescription = tr(if (visible) "Hide reference" else "Show reference")
            opacity.progress = currentReferenceOpacity()
            opacity.isEnabled = visible
            opacity.alpha = if (visible) 1f else .55f
            opacityValue.text = if (visible) "${opacity.progress}%" else "—"
            guide.refresh()
        }
        updateReferenceControls()
        referenceButton.setOnClickListener {
            if (!busy) referenceSettings(guide, ::updateReferenceControls)
        }
        toggleReference.setOnClickListener {
            if (!busy) {
                val next = when {
                    referenceMode != ReferenceMode.OFF -> ReferenceMode.OFF
                    lastVisibleReference == ReferenceMode.LINES && !guide.loadLines() -> ReferenceMode.ORIGINAL
                    else -> lastVisibleReference
                }
                selectReferenceMode(next)
                updateReferenceControls()
            }
        }
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var dragging = false
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; saveReferenceOpacity() }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser || busy || referenceMode == ReferenceMode.OFF) return
                if (referenceMode == ReferenceMode.ORIGINAL) photoOpacity = value else lineOpacity = value
                opacityValue.text = "$value%"
                guide.refresh()
                if (!dragging) saveReferenceOpacity()
            }
        })

        val zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val zoomText = label(tr("Zoom") + "  " + zoomLabel(zoomRatio), 12f, Color.WHITE).apply { gravity = Gravity.CENTER }
        val zoomSlider = SeekBar(this).apply {
            max = 1000
            isEnabled = false
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            contentDescription = tr("Zoom level")
        }
        zoomRow.addView(zoomText, LinearLayout.LayoutParams(dp(76), -2))
        zoomRow.addView(zoomSlider, LinearLayout.LayoutParams(0, -1, 1f))
        bottom.addView(zoomRow, LinearLayout.LayoutParams(-1, dp(38)))
        val presets = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val zoomPresets = listOf(.5f, 1f, 2f).associateWith { ratio ->
            button(zoomLabel(ratio)) {
                val min = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                applyZoom(if (ratio < 1f) min else ratio)
            }.apply {
                isEnabled = false
                alpha = .48f
                setPadding(dp(4), 0, dp(4), 0)
                presets.addView(this, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) })
            }
        }
        val flash = button("⚡ —") {}.apply {
            isEnabled = false
            contentDescription = tr("Flash loading")
            setPadding(dp(4), 0, dp(4), 0)
        }
        presets.addView(flash, LinearLayout.LayoutParams(0, dp(34), 1.5f))
        bottom.addView(presets, LinearLayout.LayoutParams(-1, dp(40)))

        var toggleTorch: (() -> Unit)? = null
        val exposureSlider = SeekBar(this).apply { isEnabled = false; contentDescription = tr("Exposure compensation") }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val switchCamera = button("↻") {
            cancelCountdown()
            lensFacing = oppositeLensFacing(lensFacing)
            zoomRatio = 1f
            exposureIndex = 0
            event("camera_switch_${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"}")
            showCamera()
        }.apply { isEnabled = false; contentDescription = tr("Switch camera") }
        val more = button("More") {
            val items = arrayOf(
                "Timer: ${if(timerSeconds == 0) "Off" else "${timerSeconds}s"}",
                "Reference mode",
                "Grid: ${if(gridEnabled) "On" else "Off"}",
                "Exposure compensation",
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) mirrorButtonText()
                else if (camera?.cameraInfo?.torchState?.value == TorchState.ON) "Torch on" else "Torch off",
                "Shooting tips"
            )
            AlertDialog.Builder(this).setTitle(tr("More camera settings")).setItems(items.map(::tr).toTypedArray()) { _, which ->
                when(which) {
                    0 -> timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
                    1 -> referenceButton.performClick()
                    2 -> { gridEnabled = !gridEnabled; grid.visibility = if(gridEnabled) View.VISIBLE else View.INVISIBLE }
                    3 -> if (exposureSlider.isEnabled) {
                        (exposureSlider.parent as? ViewGroup)?.removeView(exposureSlider)
                        AlertDialog.Builder(this).setTitle(tr("Exposure compensation")).setView(exposureSlider).setPositiveButton(tr("Done"), null).show()
                    } else message("Exposure compensation is not available on this lens")
                    4 -> if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        mirrorFrontCapture = !mirrorFrontCapture
                        message(if (mirrorFrontCapture) "Selfies will match the preview" else "Selfies will be saved unmirrored")
                    } else toggleTorch?.invoke() ?: message("Flash unavailable")
                    5 -> AlertDialog.Builder(this).setTitle(tr("Shooting tips")).setMessage(if (customReference) tr("Find soft light, turn the face toward it and tap the subject to focus.") else selected.position + "\n\n" + tr("Pose: ") + selected.action + "\n\n" + tr("Light: ") + selected.light).setPositiveButton(tr("Got it"), null).show()
                }
            }.show()
        }
        val sideWidth = if (landscape) 0 else dp(72)
        val sideWeight = if (landscape) 1f else 0f
        val actionGap = if (landscape) dp(6) else dp(24)
        more.setPadding(dp(4), 0, dp(4), 0)
        actions.addView(more, LinearLayout.LayoutParams(sideWidth, dp(48), sideWeight).apply { marginEnd = actionGap })
        val shutter = button("", true) {}.apply {
            isEnabled = false; contentDescription = tr("Take photo")
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), Color.rgb(92,92,92)) }
        }
        shutter.setOnClickListener { if(countdown != null) cancelCountdown() else if(!busy) schedulePhoto() }
        actions.addView(shutter, LinearLayout.LayoutParams(dp(76), dp(70)))
        actions.addView(switchCamera, LinearLayout.LayoutParams(sideWidth, dp(48), sideWeight).apply { marginStart = actionGap })
        bottom.addView(actions, LinearLayout.LayoutParams(-1, dp(72)))
        controlsReady = {
            val c = camera
            if(c != null && token == generation) {
                c.cameraInfo.zoomState.removeObservers(this)
                c.cameraInfo.zoomState.observe(this) { state ->
                    if(token == generation && state != null) {
                        zoomRatio = state.zoomRatio
                        val low = state.minZoomRatio; val high = state.maxZoomRatio
                        zoomText.text = tr("Zoom") + "  " + zoomLabel(zoomRatio)
                        zoomSlider.isEnabled = high > low
                        if (!zoomSlider.isPressed) zoomSlider.progress = if (high > low) ((zoomRatio - low) / (high - low) * 1000).toInt().coerceIn(0, 1000) else 0
                        for ((ratio, preset) in zoomPresets) {
                            val target = if (ratio < 1f && low < 1f) low else ratio
                            preset.text = zoomLabel(target)
                            preset.isEnabled = target in low..high
                            preset.alpha = if (preset.isEnabled) 1f else .48f
                        }
                    }
                }
                zoomSlider.setOnSeekBarChangeListener(sliderListener { value ->
                    val z = c.cameraInfo.zoomState.value
                    if (z != null && !busy) applyZoom(z.minZoomRatio + value / 1000f * (z.maxZoomRatio - z.minZoomRatio))
                })
                val exp = c.cameraInfo.exposureState
                if(exp.isExposureCompensationSupported) {
                    val low = exp.exposureCompensationRange.lower
                    exposureSlider.max = exp.exposureCompensationRange.upper-low
                    exposureSlider.progress = exposureIndex-low; exposureSlider.isEnabled = true
                    exposureSlider.setOnSeekBarChangeListener(sliderListener { value ->
                        revealLive?.invoke()
                        val request = ++exposureRequest
                        val future = c.cameraControl.setExposureCompensationIndex(low+value)
                        future.addListener({ if(token == generation && request == exposureRequest) {
                            runCatching { future.get() }.onSuccess { actual ->
                                exposureIndex = actual
                                event("exposure_applied_$actual")
                            }.onFailure { event("exposure_error"); message("Exposure change failed: ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}") }
                        } }, ContextCompat.getMainExecutor(this))
                    })
                }
                toggleTorch = capture?.let { photo -> configureCaptureLighting(c, photo, flash, shutter, token) }
            }
        }
        val focus = TextView(this).apply { text = "◎"; textSize = 36f; gravity = Gravity.CENTER; setTextColor(Color.YELLOW); visibility = View.INVISIBLE }
        frame.addView(focus, FrameLayout.LayoutParams(dp(52), dp(52)))
        var moved = false
        var startX = 0f
        var startY = 0f
        var focusRequest = 0
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                moved = true
                applyZoom(zoomRatio * detector.scaleFactor)
                return true
            }
        })
        preview.setOnTouchListener { _, motion ->
            if (busy) return@setOnTouchListener true
            if (motion.actionMasked == MotionEvent.ACTION_DOWN) {
                startX = motion.x
                startY = motion.y
                moved = false
            } else if (motion.pointerCount > 1 || kotlin.math.abs(motion.x - startX) > slop || kotlin.math.abs(motion.y - startY) > slop) {
                moved = true
            }
            scaler.onTouchEvent(motion)
            if (motion.actionMasked == MotionEvent.ACTION_UP && !moved) {
                val c = camera ?: return@setOnTouchListener true
                val request = ++focusRequest
                val point = preview.meteringPointFactory.createPoint(motion.x, motion.y)
                val action = FocusMeteringAction.Builder(point).build()
                focus.x = (motion.x - dp(26)).coerceIn(0f, maxOf(0f, frame.width - dp(52).toFloat()))
                focus.y = (motion.y - dp(26)).coerceIn(0f, maxOf(0f, frame.height - dp(52).toFloat()))
                focus.visibility = View.VISIBLE
                val supported = c.cameraInfo.isFocusMeteringSupported(action)
                focus.setTextColor(if (supported) Color.YELLOW else Color.LTGRAY)
                focus.postDelayed({
                    if (token == generation && request == focusRequest) focus.visibility = View.INVISIBLE
                }, 1400)
                if (supported) {
                    val future = c.cameraControl.startFocusAndMetering(action)
                    future.addListener({
                        if (token == generation && request == focusRequest) {
                            val success = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                            focus.setTextColor(if (success) Color.GREEN else Color.LTGRAY)
                            event(if (success) "focus_locked" else "focus_not_locked")
                        }
                    }, ContextCompat.getMainExecutor(this))
                } else event("focus_unavailable")
                preview.performClick()
            }; true
        }
        if (!growthPreferences.getBoolean("camera_guide_seen", false)) {
            val coach = column().apply {
                setPadding(dp(15), dp(12), dp(15), dp(12))
                background = rounded(0xE62B302F.toInt(), 18)
                elevation = dp(8).toFloat()
            }
            val coachHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            coachHead.addView(label("Your first guided shot", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
            val close = label("Got it", 13f, Color.WHITE).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(4)); background = rounded(accent, 16); isClickable = true
            }
            coachHead.addView(close, LinearLayout.LayoutParams(-2, dp(34))); coach.addView(coachHead)
            coach.addView(label("① Align position   ② Match scale   ③ Shoot when close", 13f, Color.WHITE).apply { setPadding(0, dp(6), 0, 0) })
            coach.addView(label("Switch to the front camera for selfies; use a 3s or 10s timer when you need distance.", 12f, Color.LTGRAY))
            stage.addView(coach, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(12), dp(12), dp(12), 0) })
            close.setOnClickListener {
                growthPreferences.edit().putBoolean("camera_guide_seen", true).apply()
                stage.removeView(coach)
                event("camera_guide_completed")
            }
        }
        stage.post {
            if (token != generation) return@post
            val rect = fitInside(stage.width.toFloat(), stage.height.toFloat(), reference.width.toFloat(), reference.height.toFloat())
            frame.layoutParams = FrameLayout.LayoutParams(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1), Gravity.CENTER)
            frame.post { bindCamera(preview, status, shutter, switchCamera, token) }
        }
        event("camera_open")
    }

    private fun configureCaptureLighting(c: Camera, photo: ImageCapture, flash: Button, shutter: Button, token: Int): (() -> Unit)? {
        if (!c.cameraInfo.hasFlashUnit()) {
            photo.flashMode = ImageCapture.FLASH_MODE_OFF
            flash.contentDescription = tr("Flash unavailable")
            flash.alpha = .55f
            return null
        }
        fun updateFlash() {
            photo.flashMode = if (captureFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            flash.text = tr(if (captureFlash) "⚡ On" else "⚡ Off")
            flash.contentDescription = tr(if (captureFlash) "Capture flash on" else "Capture flash off")
            flash.background = rounded(if (captureFlash) accent else Color.rgb(233, 230, 220), 14)
            flash.setTextColor(if (captureFlash) Color.WHITE else ink)
        }
        fun setTorch(enabled: Boolean, enableFlashAfter: Boolean = false) {
            if (!flash.isEnabled || busy) return
            flash.isEnabled = false
            shutter.isEnabled = false
            val future = c.cameraControl.enableTorch(enabled)
            future.addListener({
                if (token == generation && camera === c) {
                    flash.isEnabled = true
                    shutter.isEnabled = true
                    runCatching { future.get() }.onSuccess {
                        if (enabled || enableFlashAfter) captureFlash = enableFlashAfter
                        updateFlash()
                    }.onFailure { message("Could not change continuous light") }
                }
            }, ContextCompat.getMainExecutor(this))
        }
        flash.isEnabled = true
        updateFlash()
        flash.setOnClickListener {
            if (!busy && token == generation) {
                if (!captureFlash && c.cameraInfo.torchState.value == TorchState.ON) {
                    setTorch(false, enableFlashAfter = true)
                } else {
                    captureFlash = !captureFlash
                    updateFlash()
                }
            }
        }
        return { setTorch(c.cameraInfo.torchState.value != TorchState.ON) }
    }
    private var camera: Camera? = null
    private fun bindCamera(preview: PreviewView, status: TextView, shutter: Button, switchCamera: Button, token: Int) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || token != generation || screen != "camera") return@addListener
            try {
                val p = future.get(); provider = p
                val requested = selectorFor(lensFacing)
                val fallbackFacing = oppositeLensFacing(lensFacing)
                val resolvedFacing = resolveLensFacing(lensFacing, p.hasCamera(requested), p.hasCamera(selectorFor(fallbackFacing)))
                    ?: error("No camera is available on this device")
                if (resolvedFacing != lensFacing) {
                    lensFacing = resolvedFacing
                    message("The ${lensFacingName(oppositeLensFacing(lensFacing))} camera is unavailable. Switched back to the ${lensFacingName(lensFacing)} camera.")
                }
                val selector = selectorFor(lensFacing)
                val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                val stream = Preview.Builder().setTargetRotation(rotation).build()
                val photo = ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val viewport = preview.viewPort ?: error("Camera preview is not ready")
                p.unbindAll()
                camera = p.bindToLifecycle(this, selector, UseCaseGroup.Builder().setViewPort(viewport).addUseCase(stream).addUseCase(photo).build())
                stream.setSurfaceProvider(preview.surfaceProvider)
                applyZoom(zoomRatio)
                val exp = camera!!.cameraInfo.exposureState
                if (exp.isExposureCompensationSupported) {
                    exposureIndex = exposureIndex.coerceIn(exp.exposureCompensationRange.lower, exp.exposureCompensationRange.upper)
                    camera!!.cameraControl.setExposureCompensationIndex(exposureIndex)
                }
                capture = photo; status.visibility = View.GONE; shutter.isEnabled = true
                switchCamera.isEnabled = p.hasCamera(selectorFor(oppositeLensFacing(lensFacing)))
                switchCamera.text = "↻"
                controlsReady?.invoke()
                val z = camera!!.cameraInfo.zoomState.value
                event("device_${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}_sdk${android.os.Build.VERSION.SDK_INT}")
                event("camera_${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"}_zoom_range_${z?.minZoomRatio}_${z?.maxZoomRatio}_exposure_supported_${exp.isExposureCompensationSupported}")
                preview.previewStreamState.observe(this) { state -> if(token == generation) event("preview_$state") }
            } catch (_: Exception) {
                status.text = tr("Camera could not start\nTap to retry, or go back and check camera permission")
                status.setOnClickListener { showCamera() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val shotCapture = capture ?: return
        busy = true; event("shutter")
        val file = File(cacheDir, "shot-${System.currentTimeMillis()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = shouldMirrorCapture(lensFacing, mirrorFrontCapture)
        }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        shotCapture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                busy = false
                if (isDestroyed) return
                growthPreferences.edit().putBoolean("first_capture_completed", true).apply()
                lastFile = file; savedUri = null; showResult()
            }
            override fun onError(exception: ImageCaptureException) { busy = false; file.delete(); message("Capture failed. Please try again."); event("capture_failed") }
        })
    }

    private fun showResult() {
        generation++; val token = generation
        provider?.unbindAll(); capture = null; screen = "result"
        val outer = root()
        outer.addView(label("Your photo", 23f, ink).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(20), dp(12), dp(20), dp(10)) })
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val comparison = FrameLayout(this).apply { background = rounded(Color.rgb(232,229,223),24); clipToOutline = true }
        body.addView(comparison, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val loading = label("Loading your photo…", 16f, ink)
        comparison.addView(loading)
        var actual: Bitmap? = null
        val reference = activeReference()
        var mode = 2
        var comparisonGrid = false
        fun render() {
            val shot = actual ?: return
            comparison.removeAllViews()
            if (mode == 0) {
                val row = LinearLayout(this)
                listOf("Reference" to reference, "Your photo" to shot).forEach { (title, bitmap) ->
                    val pane = column(); pane.addView(label(title, 14f, ink).apply { gravity = Gravity.CENTER })
                    pane.addView(GridReferenceView(bitmap, comparisonGrid), LinearLayout.LayoutParams(-1, 0, 1f))
                    row.addView(pane, LinearLayout.LayoutParams(0, -1, 1f))
                }
                comparison.addView(row, FrameLayout.LayoutParams(-1, -1))
            } else comparison.addView(GridReferenceView(if (mode == 1) reference else shot, comparisonGrid), FrameLayout.LayoutParams(-1, -1))
        }
        val file = lastFile ?: return
        worker.execute {
            val bitmap = runCatching { loadBitmap(file, 1800) }.getOrNull()
            runOnUiThread { if (token == generation && !isDestroyed) {
                actual = bitmap
                if (bitmap != null) render() else loading.text = tr("Preview failed to load. You can still retry saving the original.")
            } }
        }
        val controls = column().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        val panel = ScrollView(this); panel.addView(controls)
        body.addView(panel, if (landscape) LinearLayout.LayoutParams(dp(300), -1) else LinearLayout.LayoutParams(-1, dp(330)))
        val modes = LinearLayout(this)
        val options = if (landscape) listOf("Side by side" to 0, "Reference" to 1, "Photo" to 2) else listOf("Reference" to 1, "Photo" to 2)
        options.forEach { (title, index) -> modes.addView(button(title) { mode = index; render() }, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        modes.addView(button("Grid") { comparisonGrid = !comparisonGrid; render() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(modes); space(controls, 6)
        val save = button(if (savedUri != null) "Saved to gallery" else "Save to gallery", true) {}
        save.setOnClickListener { if (!busy && savedUri == null) savePhoto(file, save) }
        controls.addView(save); space(controls, 6)
        val shareScene = if (customReference) null else rewardScene(selected, filter.scene)
        val rewardAvailable = SHARE_GATING_ENABLED && shareScene != null && shareScene !in unlockedScenes()
        val shareCard = column().apply {
            setPadding(dp(15), dp(13), dp(15), dp(13)); background = rounded(Color.rgb(43, 50, 48), 18)
            addView(label(if (rewardAvailable) "Share to unlock ${sceneTitle(shareScene!!)}" else "Send this shot idea to a friend", 17f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(label(if (rewardAvailable) "Share your finished shot card; the original stays watermark-free. Choose a share target to unlock this scene." else "Creates a separate share card without changing your original photo.", 12f, Color.LTGRAY).apply { setPadding(0, dp(4), 0, dp(9)) })
            addView(button("Create shot card", true) { shareResult(file, shareScene.takeIf { SHARE_GATING_ENABLED }) })
        }
        controls.addView(shareCard); space(controls, 8)
        controls.addView(button("Keep settings and shoot again") { showCamera() }); space(controls, 6)
        controls.addView(button("Rate this photo (optional)") {
            val labels = arrayOf("Composition is close", "Pose is close", "Lighting is off", "I want to retake")
            val values = arrayOf("composition_ok", "pose_ok", "lighting_issue", "retry_wanted")
            AlertDialog.Builder(this).setTitle(tr("How does it compare?")).setItems(labels.map(::tr).toTypedArray()) { _, which -> event(values[which]); message("Recorded. This does not affect saving.") }.show()
        })
    }

    private fun savePhoto(file: File, button: Button) {
        busy = true; button.text = tr("Saving…")
        worker.execute {
            var uri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ShotGuide_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ShotGuide")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create photo")
                contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Unable to write photo")
                check(contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
                val result = uri
                runOnUiThread { busy = false; savedUri = result; button.text = tr("Saved to gallery"); message("Saved to Pictures/ShotGuide"); event("saved") }
            } catch (_: Exception) {
                uri?.let { runCatching { contentResolver.delete(it, null, null) } }
                runOnUiThread { busy = false; button.text = tr("Retry save"); message("Save failed. Check free storage and try again.") }
            }
        }
    }

    private fun shareResult(file: File, rewardScene: String?) {
        if (busy) return
        busy = true
        val template = selected.takeUnless { customReference }
        event("share_card_requested")
        worker.execute {
            try {
                val card = createShareCard(file, template)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", card)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, shareCardText(template))
                    clipData = ClipData.newRawUri("ShotGuide shot card", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooserTitle = tr("Share this shot idea")
                val chooser = if (template != null && rewardScene != null) {
                    val callback = Intent(this, ShareTargetReceiver::class.java).apply {
                        action = ACTION_SHARE_TARGET_CHOSEN
                        putExtra(EXTRA_REWARD_SCENE, rewardScene)
                        putExtra(EXTRA_REWARD_SHOT, template.id)
                    }
                    val requestCode = (System.currentTimeMillis() and 0x7fffffff).toInt()
                    val pending = PendingIntent.getBroadcast(this, requestCode, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    Intent.createChooser(send, chooserTitle, pending.intentSender)
                } else {
                    Intent.createChooser(send, chooserTitle)
                }
                runOnUiThread {
                    busy = false
                    event("share_sheet_open")
                    runCatching { startActivity(chooser) }.onFailure { message("No sharing app is available") }
                }
            } catch (_: Exception) {
                runOnUiThread { busy = false; event("share_card_failed"); message("Could not create the shot card. Please try again.") }
            }
        }
    }

    private fun shareCardText(template: Shot?): String {
        if (template == null) return tr("I took this photo in ShotGuide using my own reference.")
        return if (AppLanguage.effective(this) == AppLanguage.CHINESE) {
            "我用 ShotGuide 复刻了「${template.title}」（模板 ${template.id}）。你也可以试试！"
        } else {
            "I used ShotGuide to recreate ‘${template.title}’ (template ${template.id}). Try this shot too!"
        }
    }

    /** Builds a separate card without modifying or watermarking the saved original. */
    private fun createShareCard(file: File, template: Shot?): File {
        val photo = loadBitmap(file, 1800)
        val width = 1080
        val height = 1440
        val card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(paper)
        val photoArea = fitInside(984f, 1000f, photo.width.toFloat(), photo.height.toFloat())
        val destination = RectF(48f + photoArea.left, 48f + photoArea.top, 48f + photoArea.left + photoArea.width, 48f + photoArea.top + photoArea.height)
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(photo, null, destination, imagePaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 50f; typeface = Typeface.DEFAULT_BOLD }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(91, 96, 92); textSize = 31f }
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
        val title = if (template == null) tr("Custom reference") else "${tr("I recreated")} | ${template.title}"
        canvas.drawText(fitCardText(title, titlePaint, 984f), 48f, 1155f, titlePaint)
        if (template != null) {
            canvas.drawText(fitCardText(template.subtitle, bodyPaint, 984f), 48f, 1210f, bodyPaint)
            canvas.drawText(fitCardText(tr("How: ${template.action}"), bodyPaint, 984f), 48f, 1270f, bodyPaint)
        }
        val byline = if (template == null) "ShotGuide" else "ShotGuide · ${tr("Template")} ${template.id}"
        canvas.drawText(byline, 48f, 1360f, brandPaint)
        val directory = File(cacheDir, "share_cards").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach(File::delete)
        val output = File(directory, "shot-${template?.id ?: "custom"}-${System.currentTimeMillis()}.jpg")
        output.outputStream().use { check(card.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        photo.recycle(); card.recycle()
        return output
    }

    private fun fitCardText(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        val suffix = "…"
        val count = paint.breakText(value, true, maxWidth - paint.measureText(suffix), null)
        return value.take(count.coerceAtLeast(0)) + suffix
    }

    private fun referenceSettings(guide: PoseOverlay, onModeChanged: () -> Unit) {
        val panel = column().apply { setPadding(dp(22), 0, dp(22), dp(8)) }
        val modes = RadioGroup(this)
        val choices = ReferenceMode.entries.associateWith { mode ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = tr(mode.title)
                minHeight = dp(42)
                modes.addView(this)
            }
        }
        panel.addView(modes)
        val color = button("Black / white") {
            whiteLines = !whiteLines
            getPreferences(MODE_PRIVATE).edit().putBoolean("white_lines", whiteLines).apply()
            guide.invalidate()
        }
        panel.addView(color, LinearLayout.LayoutParams(-1, dp(42)))

        fun updateControls() {
            modes.check(choices.getValue(referenceMode).id)
            color.visibility = if (referenceMode == ReferenceMode.LINES) View.VISIBLE else View.GONE
        }
        for ((mode, choice) in choices) {
            choice.setOnClickListener {
                if (mode == ReferenceMode.LINES && !guide.loadLines()) {
                    if (customReference) referenceModel.generateMissingOnce()
                    message(if (referenceModel.state.value?.working == true) "Generating line guide" else "Line guide unavailable · use the original or retry from your reference")
                } else {
                    selectReferenceMode(mode)
                    onModeChanged()
                }
                updateControls()
            }
        }
        updateControls()
        val dialog = AlertDialog.Builder(this).setTitle(tr("Reference mode"))
            .setView(ScrollView(this).apply { addView(panel) })
            .setNeutralButton(tr("View full photo")) { _, _ -> referenceDialog() }
            .setPositiveButton(tr("Done"), null)
            .create()
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    private fun currentReferenceOpacity(): Int = if (referenceMode == ReferenceMode.ORIGINAL) photoOpacity else lineOpacity

    private fun saveReferenceOpacity() {
        getPreferences(MODE_PRIVATE).edit().putInt("line_opacity", lineOpacity).putInt("photo_opacity", photoOpacity).apply()
    }

    private fun referenceDialog(photo: Bitmap = activeReference()) {
        val box = column().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        box.addView(GridReferenceView(photo).apply { contentDescription = tr("View full photo") }, LinearLayout.LayoutParams(-1, dp(340)))
        space(box)
        box.addView(label(if (customReference) "Borrow the pose and composition; the surroundings do not need to match exactly." else selected.position, 15f))
        if (!customReference) { space(box, 8); box.addView(label("Pose: ${selected.action}", 15f)); space(box, 8); box.addView(label("Light: ${selected.light}", 14f)) }
        AlertDialog.Builder(this).setTitle(tr("Shooting reference")).setView(ScrollView(this).apply { addView(box) }).setPositiveButton(tr("Keep shooting"), null).show()
    }

    private fun feedbackDialog() {
        val data = runCatching { File(filesDir, "trial.csv").readLines() }.getOrDefault(emptyList())
        val summary = if (AppLanguage.effective(this) == AppLanguage.CHINESE)
            "拍摄次数：${data.count { it.endsWith(",shutter") }}\n保存次数：${data.count { it.endsWith(",saved") }}\n满意评价：${data.count { it.endsWith(",satisfied") }}\n不满意评价：${data.count { it.endsWith(",unsatisfied") }}\n\n以上为操作次数，不代表独立用户数或成功率。记录仅保留在本机，不包含照片。"
        else "Photos taken: ${data.count { it.endsWith(",shutter") }}\nPhotos saved: ${data.count { it.endsWith(",saved") }}\nPositive ratings: ${data.count { it.endsWith(",satisfied") }}\nNegative ratings: ${data.count { it.endsWith(",unsatisfied") }}\n\nThese are action counts, not unique users or success rates. Records stay on this device and never include photos."
        AlertDialog.Builder(this).setTitle(if (AppLanguage.effective(this) == AppLanguage.CHINESE) "本机测试记录" else "On-device test log").setMessage(summary).setPositiveButton(tr("Close"), null).setNeutralButton(tr("Copy log")) { _, _ ->
            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("ShotGuide test log", "time,shot,event\n" + data.joinToString("\n")))
            message("Copied. Paste it into your feedback message.")
        }.show()
    }

    private fun loadBitmap(file: File, max: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > max) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Invalid image")
        val matrix = Matrix()
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it != bitmap) bitmap.recycle() }
    }

    private fun drawThirds(canvas: Canvas, box: FitBox) {
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        // Thin white lines with a subtle dark border remain visible on light/dark scenes.
        for (border in listOf(true, false)) {
            grid.color = if (border) 0x70000000 else 0xBFFFFFFF.toInt()
            grid.strokeWidth = resources.displayMetrics.density * if (border) 2f else .8f
            for (i in 1..2) {
                val x = box.left + box.width * i / 3f
                val y = box.top + box.height * i / 3f
                canvas.drawLine(x, box.top, x, box.top + box.height, grid)
                canvas.drawLine(box.left, y, box.left + box.width, y, grid)
            }
        }
    }

    inner class GridLayer(private val imageWidth: Int, private val imageHeight: Int) : View(this@MainActivity) {
        init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            drawThirds(canvas, fitInside(width.toFloat(), height.toFloat(), imageWidth.toFloat(), imageHeight.toFloat()))
        }
    }

    inner class GridReferenceView(private val bitmap: Bitmap, var showGrid: Boolean = true) : View(this@MainActivity) {
        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            val box = fitInside(width.toFloat(), height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawBitmap(bitmap, null, RectF(box.left, box.top, box.left + box.width, box.top + box.height), imagePaint)
            if (showGrid) drawThirds(canvas, box)
        }
    }

    private inner class PoseOverlay(private val photo: Bitmap) : View(this@MainActivity) {
        private var lineart: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val white = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        private val black = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)

        fun loadLines(): Boolean {
            if (lineart == null) {
                lineart = if (customReference) BitmapFactory.decodeFile(referenceModel.linesFile.path)
                else if (selected.overlay != null) templateLines else null
            }
            return lineart != null
        }

        fun refresh() {
            visibility = if (referenceMode == ReferenceMode.OFF) View.INVISIBLE else View.VISIBLE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (referenceMode == ReferenceMode.OFF) return
            val usePhoto = referenceMode == ReferenceMode.ORIGINAL
            val bitmap = if (usePhoto) photo else lineart ?: return
            val rect = fitInside(width.toFloat(), height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat())
            paint.alpha = currentReferenceOpacity() * 255 / 100
            paint.colorFilter = if (usePhoto) null else if (whiteLines) white else black
            canvas.drawBitmap(bitmap, null, RectF(rect.left, rect.top, rect.left + rect.width, rect.top + rect.height), paint)
        }
    }
    private fun sliderListener(change: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if(user) change(value) }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    }
    private fun zoomLabel(ratio: Float): String = String.format(java.util.Locale.US, "%.1f×", ratio)

    private fun applyZoom(value: Float) {
        val c = camera ?: return
        val state = c.cameraInfo.zoomState.value ?: return
        val desired = value.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        revealLive?.invoke()
        val request = ++zoomRequest
        val future = c.cameraControl.setZoomRatio(desired)
        future.addListener({ if(request == zoomRequest && screen == "camera" && camera === c) {
            runCatching { future.get() }.onSuccess { event("zoom_applied_$desired") }.onFailure {
                event("zoom_error"); message("Zoom change failed: ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}")
            }
        } }, ContextCompat.getMainExecutor(this))
    }
    private fun selectorFor(facing: Int): CameraSelector = if (facing == CameraSelector.LENS_FACING_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    private fun mirrorButtonText(): String = if (mirrorFrontCapture) "Mirror on" else "Mirror off"
    private fun cancelCountdown() {
        countdown?.let { handler.removeCallbacks(it); countdown = null; busy = false }
        countdownLabel?.visibility = View.GONE
    }

    private fun schedulePhoto() {
        if (capture == null || busy) return
        if (timerSeconds == 0) { takePhoto(); return }
        var remaining = timerSeconds
        busy = true
        val task = object : Runnable {
            override fun run() {
                if (screen != "camera" || isDestroyed) { busy = false; countdown = null; return }
                if (remaining == 0) { countdown = null; countdownLabel?.visibility = View.GONE; busy = false; takePhoto(); return }
                countdownLabel?.visibility = View.VISIBLE; countdownLabel?.text = remaining.toString(); remaining--; handler.postDelayed(this, 1000)
            }
        }
        countdown = task; handler.post(task)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelCountdown()
        if (busy) return
        when (screen) {
            "reference" -> showCustomReference()
            "camera" -> showCamera()
            "result" -> showResult()
            "shot" -> showShot()
            "onboarding" -> showOnboarding()
            else -> showHome()
        }
    }
    override fun onStop() {
        cancelCountdown()
        super.onStop()
    }

    override fun onDestroy() { generation++; handler.removeCallbacksAndMessages(null); super.onDestroy(); worker.shutdown() }
}
