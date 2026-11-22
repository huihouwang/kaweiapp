package com.summer.shotguide

import android.content.Context
import org.json.JSONObject

data class Scene(val id: String, val title: String, val description: String)
data class Shot(
    val id: String, val title: String, val subtitle: String,
    val scenes: Set<String>, val pose: String, val orientation: String,
    val photo: String, val overlay: String?,
    val position: String, val action: String, val light: String
)
data class ShotFilter(val scene: String? = null, val pose: String? = null, val orientation: String? = null) {
    fun matches(shot: Shot) = (scene == null || scene in shot.scenes) &&
        (pose == null || pose == shot.pose) && (orientation == null || orientation == shot.orientation)
}
data class ShotCatalog(val scenes: List<Scene>, val shots: List<Shot>) {
    companion object {
        fun load(context: Context): ShotCatalog {
            val assets = context.assets
            val chinese = AppLanguage.effective(context) == AppLanguage.CHINESE
            val data = JSONObject(assets.open("catalog-v1.json").bufferedReader().use { it.readText() })
            require(data.getInt("version") == 1) { "Unsupported catalog version" }
            val sceneData = data.getJSONArray("scenes")
            val scenes = (0 until sceneData.length()).map { i -> sceneData.getJSONObject(i).let {
                val id = it.getString("id")
                Scene(id, if (chinese) it.getString("title") else sceneTitle(id), if (chinese) it.getString("description") else sceneDescription(id))
            } }
            val shotData = data.getJSONArray("shots")
            val shots = (0 until shotData.length()).map { i -> shotData.getJSONObject(i).let {
                val tags = it.getJSONArray("scenes")
                val id = it.getString("id")
                val pose = it.getString("pose")
                val orientation = it.getString("orientation")
                val scene = it.getJSONArray("scenes").optString(0)
                val popularity = Regex("(\\d+)$").find(it.getString("subtitle"))?.value ?: ""
                Shot(id, if (chinese) it.getString("title") else "${sceneTitle(scene)} ${poseName(pose)} ${id.takeLast(2)}",
                    if (chinese) it.getString("subtitle") else "${poseName(pose)} · ${if (orientation == "landscape") "Landscape" else "Portrait"}${if (popularity.isNotEmpty()) " · Popularity $popularity" else ""}",
                    (0 until tags.length()).map { n -> tags.getString(n) }.toSet(),
                    pose, orientation, it.getString("photo"),
                    it.optString("overlay").takeIf { path -> path.isNotBlank() },
                    if (chinese) it.getString("position") else positionGuide(orientation),
                    if (chinese) it.getString("action") else actionGuide(pose),
                    if (chinese) it.getString("light") else lightGuide(scene))
            } }
            require(scenes.map { it.id }.distinct().size == scenes.size)
            require(shots.isNotEmpty() && shots.map { it.id }.distinct().size == shots.size)
            require(shots.all { shot -> shot.scenes.all { id -> scenes.any { it.id == id } } })
            require(shots.all { it.overlay != null }) { "Every bundled photo must have a line-art overlay" }
            return ShotCatalog(scenes, shots)
        }

        private fun sceneTitle(id: String) = when (id) {
            "street" -> "Street"
            "cafe" -> "Cafe"
            "park" -> "Park"
            "sea" -> "Seaside"
            "grass" -> "Grassland"
            "mountain" -> "Mountain"
            else -> "Anywhere"
        }

        private fun sceneDescription(id: String) = when (id) {
            "street" -> "Street corners, storefronts and city roads for relaxed portraits"
            "cafe" -> "Tables, windows and soft indoor light"
            "park" -> "Lawns, benches and shade for easy environmental portraits"
            "sea" -> "Coastlines, railings and beaches with the sea in frame"
            "grass" -> "Open fields and sky for expansive, dynamic poses"
            "mountain" -> "Trails, viewpoints and rocks with depth and scale"
            else -> "Simple compositions that work almost anywhere"
        }

        private fun poseName(pose: String) = when (pose) {
            "stand" -> "Standing"
            "sit" -> "Sitting"
            "walk" -> "Walking"
            "lie" -> "Lying"
            else -> "Natural"
        }

        private fun positionGuide(orientation: String) = if (orientation == "landscape")
            "Rear camera · 1×. Keep the phone level, align the horizon, then place the subject near a rule-of-thirds line."
        else
            "Rear camera · 1×. Keep the phone upright at waist-to-chest height and leave space above the head and below the feet."

        private fun actionGuide(pose: String) = when (pose) {
            "stand" -> "Shift your weight onto one leg, relax your shoulders and leave a little space between your arms and body."
            "sit" -> "Sit slightly forward, stagger your legs and rest your hands naturally on your knees or beside you."
            "walk" -> "Walk slowly and naturally, look away from the lens sometimes, and take several shots in a row."
            "lie" -> "Create a clear body silhouette first, then relax your hands and feet without stacking all four limbs."
            else -> "Relax your shoulders and copy the reference loosely rather than forcing an exact match."
        }

        private fun lightGuide(scene: String) = when (scene) {
            "street" -> "Look for open shade or soft side light, with the face turned toward the brighter side of the street."
            "cafe" -> "Stay near a window but out of direct sun, letting the window light reach the face from the front or side."
            "park" -> "Use full shade and avoid patchy spots of sunlight on the face."
            "sea" -> "In strong sun, turn sideways to the light, face the open sky and lower exposure slightly."
            "grass" -> "Avoid harsh midday overhead light. In backlight, expose for the face rather than only the sky."
            "mountain" -> "Use side light to reveal the landscape. If the face is dark, turn toward the open sky."
            else -> "Choose soft, even light and keep the subject facing the brighter open area."
        }
    }
}
