package com.summer.shotguide

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

const val GROWTH_PREFERENCES = "growth_access"
const val EXTRA_REWARD_SCENE = "reward_scene"
const val EXTRA_REWARD_SHOT = "reward_shot"
const val ACTION_SHARE_TARGET_CHOSEN = "com.summer.shotguide.SHARE_TARGET_CHOSEN"

/** Product switch: keep every bundled template open during validation. */
const val SHARE_GATING_ENABLED = false

/** Retained for a possible later experiment; ignored while gating is disabled. */
const val FREE_SHOTS_PER_SCENE = 5

fun shotNumber(shot: Shot): Int? = shot.id.substringAfterLast('-').toIntOrNull()

fun isShareLocked(shot: Shot, unlockedScenes: Set<String>): Boolean {
    if (!SHARE_GATING_ENABLED) return false
    if (shot.scenes.any(unlockedScenes::contains)) return false
    return (shotNumber(shot) ?: 1) > FREE_SHOTS_PER_SCENE
}

fun rewardScene(shot: Shot, preferredScene: String?): String? =
    preferredScene?.takeIf(shot.scenes::contains) ?: shot.scenes.sorted().firstOrNull()

/**
 * Android can report that a share target was selected, but cannot prove that a
 * social post was finally published. The MVP deliberately rewards target
 * selection instead of pretending to verify publication.
 */
class ShareTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHARE_TARGET_CHOSEN) return
        val scene = intent.getStringExtra(EXTRA_REWARD_SCENE)?.takeIf(String::isNotBlank) ?: return
        val shot = intent.getStringExtra(EXTRA_REWARD_SHOT).orEmpty()
        val preferences = context.getSharedPreferences(GROWTH_PREFERENCES, Context.MODE_PRIVATE)
        val unlocked = preferences.getStringSet("unlocked_scenes", emptySet()).orEmpty().toMutableSet()
        unlocked += scene
        preferences.edit()
            .putStringSet("unlocked_scenes", unlocked)
            .putString("last_share_reward_scene", scene)
            .putString("last_share_reward_shot", shot)
            .apply()
    }
}
