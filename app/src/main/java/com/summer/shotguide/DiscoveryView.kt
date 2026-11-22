package com.summer.shotguide

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Native implementation of the approved ui-prototype discovery hierarchy. */
class DiscoveryView(
    context: Context,
    private val catalog: ShotCatalog,
    initialFilter: ShotFilter,
    initialPosition: Int,
    private val shotPhoto: (Shot) -> Bitmap,
    private val isLocked: (Shot) -> Boolean,
    private val showStarter: Boolean,
    private val onStarter: () -> Unit,
    private val onFilter: (ShotFilter) -> Unit,
    private val onSelect: (Shot, Int) -> Unit,
    private val onLocked: (Shot) -> Unit,
    private val onRecent: () -> Unit,
    private val onImport: () -> Unit,
    private val onCustom: (() -> Unit)?,
    private val onLanguage: () -> Unit,
    private val onFeedback: () -> Unit
) : LinearLayout(context) {
    private val ink = Color.rgb(23, 23, 20)
    private val muted = Color.rgb(119, 115, 109)
    private val accent = Color.rgb(255, 92, 80)
    private val accentSoft = Color.rgb(255, 231, 226)
    private val lime = Color.rgb(223, 255, 114)
    private val surface = Color.WHITE
    private val page = Color.rgb(245, 243, 239)
    private var filter = initialFilter

    init {
        orientation = VERTICAL
        setBackgroundColor(page)
        render()
    }

    private fun render() {
        removeAllViews()
        if (filter.scene == null) renderHome() else renderScene()
    }

    private fun update(next: ShotFilter) {
        filter = next
        onFilter(next)
        render()
    }

    private fun renderHome() {
        val scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val content = column().apply { setPadding(dp(17), dp(12), dp(17), dp(24)) }
        scroll.addView(content); addView(scroll, LayoutParams(-1, 0, 1f))

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(text("S", 16f, lime).apply {
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; background = rounded(ink, 10)
        }, LayoutParams(dp(34), dp(34)))
        brand.addView(text("ShotGuide", 23f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(9), 0, 0, 0) })
        top.addView(brand, LayoutParams(0, -2, 1f))
        top.addView(circleButton("⌕") { scenePicker() }, LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(7) })
        top.addView(circleButton("☰") { utilityMenu() }, LayoutParams(dp(42), dp(42)))
        content.addView(top)

        content.addView(text("Start with where you are", 12f, muted).apply { setPadding(0, dp(18), 0, dp(3)) })
        content.addView(text("How do you want to shoot?", 28f).apply { typeface = Typeface.DEFAULT_BOLD })
        content.addView(text("⌕   Find seaside, cafe, park...", 13f, Color.rgb(145,139,132)).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0); background = rounded(surface, 16); isClickable = true
            setOnClickListener { scenePicker() }
        }, LayoutParams(-1, dp(50)).apply { topMargin = dp(14) })

        val recent = catalog.shots.first()
        val continueCard = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), dp(9), dp(9), dp(9)); background = rounded(ink, 19); isClickable = true
            setOnClickListener { onRecent() }
        }
        continueCard.addView(photo(recent, 88, 72, 13))
        val recentCopy = column().apply { setPadding(dp(12), 0, 0, 0) }
        recentCopy.addView(text("CONTINUE", 10f, lime).apply { typeface = Typeface.DEFAULT_BOLD })
        recentCopy.addView(text("Return to your last setup", 14f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, 0) })
        recentCopy.addView(text("Your camera settings are kept", 10f, Color.LTGRAY))
        continueCard.addView(recentCopy, LayoutParams(0, -2, 1f))
        continueCard.addView(text("→", 18f).apply { gravity = Gravity.CENTER; background = rounded(Color.WHITE, 30) }, LayoutParams(dp(36), dp(36)))
        content.addView(continueCard, LayoutParams(-1, -2).apply { topMargin = dp(14) })

        if (showStarter) content.addView(starterCard(), LayoutParams(-1, -2).apply { topMargin = dp(12) })

        section(content, "Where are you now?", "Choose your surroundings and see repeatable shots")
        addGrid(content, catalog.scenes) { scene -> sceneCard(scene) }

        section(content, "Easy solo shots", "Start with the action that feels easiest")
        val quick = LinearLayout(context)
        listOf("Standing" to "stand", "Sitting" to "sit", "Walking" to "walk").forEach { (title, pose) ->
            quick.addView(quickButton(title) {
                val targetScene = catalog.scenes.firstOrNull { scene -> catalog.shots.any { scene.id in it.scenes && it.pose == pose } }?.id
                update(ShotFilter(scene = targetScene, pose = pose))
            }, LayoutParams(0, dp(76), 1f).apply { marginEnd = dp(7) })
        }
        content.addView(quick)

        section(content, "Popular this week", "Templates you can recreate in real places")
        addGrid(content, catalog.shots.take(6)) { shot -> shotCard(shot) }

        val utilities = LinearLayout(context).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) }
        utilities.addView(text("＋ Import", 13f, muted).apply { gravity = Gravity.CENTER; isClickable = true; setOnClickListener { onImport() } }, LayoutParams(0, dp(44), 1f))
        if (onCustom != null) utilities.addView(text("My reference", 13f, muted).apply { gravity = Gravity.CENTER; isClickable = true; setOnClickListener { onCustom.invoke() } }, LayoutParams(0, dp(44), 1f))
        utilities.addView(text("Feedback", 13f, muted).apply { gravity = Gravity.CENTER; isClickable = true; setOnClickListener { onFeedback() } }, LayoutParams(0, dp(44), 1f))
        content.addView(utilities)
    }

    private fun renderScene() {
        val scene = catalog.scenes.firstOrNull { it.id == filter.scene } ?: run { update(ShotFilter()); return }
        val candidates = catalog.shots.filter { scene.id in it.scenes }
        val visible = candidates.filter(filter::matches)
        val cover = candidates.firstOrNull() ?: catalog.shots.first()
        val scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val content = column().apply { setPadding(dp(17), dp(10), dp(17), dp(25)) }
        scroll.addView(content); addView(scroll, LayoutParams(-1, 0, 1f))

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("← Back", 15f).apply { gravity = Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { update(ShotFilter()) } }, LayoutParams(0, dp(44), 1f))
        top.addView(text("Scene Pack", 15f).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }, LayoutParams(0, dp(44), 1f))
        top.addView(circleButton("•••") { utilityMenu() }, LayoutParams(dp(42), dp(42)))
        content.addView(top)

        val hero = FrameLayout(context).apply { background = rounded(Color.DKGRAY, 24); clipToOutline = true }
        hero.addView(ImageView(context).apply { setImageBitmap(shotPhoto(cover)); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(context).apply { setBackgroundColor(0x50000000) }, FrameLayout.LayoutParams(-1, -1))
        val heroCopy = column().apply { setPadding(dp(16), dp(12), dp(16), dp(15)) }
        heroCopy.addView(text(scene.title, 28f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        heroCopy.addView(text(scene.description, 11f, Color.LTGRAY))
        hero.addView(heroCopy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        content.addView(hero, LayoutParams(-1, dp(195)).apply { topMargin = dp(10); bottomMargin = dp(12) })

        val sceneChips = LinearLayout(context)
        catalog.scenes.forEach { item -> sceneChips.addView(chip(item.title, item.id == scene.id) { update(filter.copy(scene = item.id)) }, LayoutParams(-2, dp(40)).apply { marginEnd = dp(7) }) }
        content.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(sceneChips) })
        val poseChips = LinearLayout(context)
        listOf(null to "All", "stand" to "Standing", "sit" to "Sitting", "walk" to "Walking", "lie" to "Lying").forEach { (pose, title) ->
            poseChips.addView(chip(title, filter.pose == pose) { update(filter.copy(pose = pose)) }, LayoutParams(-2, dp(40)).apply { marginEnd = dp(7) })
        }
        content.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(poseChips) }, LayoutParams(-1, dp(48)))
        content.addView(text("${visible.size} matching shots", 12f, muted).apply { setPadding(dp(2), dp(5), 0, dp(10)) })
        if (visible.isEmpty()) content.addView(text("No templates match this combination yet.\nTry another pose or scene.", 16f, muted).apply { gravity = Gravity.CENTER }, LayoutParams(-1, dp(180)))
        else addGrid(content, visible) { shot -> shotCard(shot) }
    }

    private fun starterCard(): View = column().apply {
        setPadding(dp(17), dp(14), dp(17), dp(14)); background = rounded(accentSoft, 19)
        addView(text("YOUR FIRST SHOT · ABOUT 1 MIN", 13f, accent).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(text("Follow one simple template for your first photo", 17f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(5), 0, dp(7)) })
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("① Choose  ② Align  ③ Shoot", 12f, muted), LayoutParams(0, -2, 1f))
        row.addView(chip("Start", true, onStarter), LayoutParams(-2, dp(40)))
        addView(row)
    }

    private fun sceneCard(scene: Scene): View {
        val cover = catalog.shots.firstOrNull { scene.id in it.scenes } ?: catalog.shots.first()
        return FrameLayout(context).apply {
            background = rounded(Color.DKGRAY, 20); clipToOutline = true; isClickable = true; setOnClickListener { update(ShotFilter(scene = scene.id)) }
            addView(ImageView(context).apply { setImageBitmap(shotPhoto(cover)); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { setBackgroundColor(0x43000000) }, FrameLayout.LayoutParams(-1, -1))
            val copy = column().apply { setPadding(dp(13), dp(8), dp(11), dp(11)) }
            copy.addView(text(scene.title, 17f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
            copy.addView(text(scene.description, 9f, Color.LTGRAY))
            addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    private fun shotCard(shot: Shot): View {
        val locked = isLocked(shot)
        return column().apply {
            isClickable = true; contentDescription = "${shot.title}, ${if(locked) tr("share to unlock") else tr("view shot guide")}"
            setOnClickListener { if(locked) onLocked(shot) else onSelect(shot, 0) }
            val frame = FrameLayout(context).apply { background = rounded(Color.LTGRAY, 18); clipToOutline = true }
            frame.addView(ImageView(context).apply { setImageBitmap(shotPhoto(shot)); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = if(locked) .55f else 1f }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(text(if(locked) "Share to unlock" else poseName(shot.pose), 10f, ink).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4)); background = rounded(0xEFFFFFFF.toInt(), 20) }, FrameLayout.LayoutParams(-2, dp(28), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(8),0,0,dp(8)) })
            if(locked) frame.addView(text("Share once\nto unlock this scene", 13f, Color.WHITE).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(13),dp(8),dp(13),dp(8)); background = rounded(0xD920201E.toInt(), 14) }, FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
            addView(frame, LayoutParams(-1, dp(205)))
            addView(text(shot.title, 14f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2),dp(8),dp(2),0) })
            addView(text(shot.subtitle, 10f, muted).apply { setPadding(dp(2),0,dp(2),dp(5)); maxLines = 1 })
        }
    }

    private fun <T> addGrid(parent: LinearLayout, items: List<T>, factory: (T) -> View) {
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { gravity = Gravity.TOP }
            pair.forEachIndexed { index, item -> row.addView(factory(item), LayoutParams(0, -2, 1f).apply { if(index == 0) marginEnd = dp(5) else marginStart = dp(5); bottomMargin = dp(12) }) }
            if(pair.size == 1) row.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(5) })
            parent.addView(row)
        }
    }

    private fun section(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(text(title, 19f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(1),dp(22),0,0) })
        parent.addView(text(subtitle, 10f, muted).apply { setPadding(dp(1),dp(2),0,dp(11)) })
    }

    private fun quickButton(value: String, action: () -> Unit) = text(value, 13f).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; background = rounded(surface,18); isClickable = true; setOnClickListener { action() } }
    private fun circleButton(value: String, action: () -> Unit) = text(value, 17f).apply { gravity = Gravity.CENTER; background = rounded(surface,50); isClickable = true; setOnClickListener { action() } }
    private fun chip(value: String, active: Boolean, action: () -> Unit) = text(value, 12f, if(active) Color.WHITE else ink).apply { gravity = Gravity.CENTER; setPadding(dp(13),0,dp(13),0); background = rounded(if(active) ink else surface,24); isClickable = true; setOnClickListener { action() } }
    private fun scenePicker() {
        val names = catalog.scenes.map { it.title }.toTypedArray()
        AlertDialog.Builder(context).setTitle(tr("Choose your scene")).setItems(names) { _, index -> update(ShotFilter(scene = catalog.scenes[index].id)) }.setNegativeButton(tr("Cancel"), null).show()
    }
    private fun utilityMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Import a reference photo" to onImport
        if (onCustom != null) actions += "Open my reference" to onCustom
        actions += "Language" to onLanguage
        actions += "Send feedback" to onFeedback
        AlertDialog.Builder(context).setTitle(tr("More")).setItems(actions.map { tr(it.first) }.toTypedArray()) { _, index ->
            actions[index].second.invoke()
        }.setNegativeButton(tr("Cancel"), null).show()
    }
    private fun photo(shot: Shot, width: Int, height: Int, radius: Int) = ImageView(context).apply { setImageBitmap(shotPhoto(shot)); scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(Color.DKGRAY,radius); clipToOutline = true; layoutParams = LayoutParams(dp(width),dp(height)) }
    private fun poseName(value: String) = when(value) { "stand" -> "Standing"; "sit" -> "Sitting"; "walk" -> "Walking"; "lie" -> "Lying"; else -> "Shot" }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun tr(value: String) = AppLanguage.text(context, value)
    private fun text(value: String, size: Float, color: Int = ink) = TextView(context).apply { text = tr(value); textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(),1f) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
