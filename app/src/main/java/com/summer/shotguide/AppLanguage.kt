package com.summer.shotguide

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLanguage {
    private const val PREFS = "app_settings"
    private const val KEY = "language"
    const val SYSTEM = "system"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    fun saved(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
    fun effective(context: Context): String {
        val saved = saved(context)
        if (saved != SYSTEM) return saved
        return if (Locale.getDefault().language.startsWith("zh")) CHINESE else ENGLISH
    }
    fun set(context: Context, value: String) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    fun wrap(base: Context): Context {
        val selected = saved(base)
        if (selected == SYSTEM) return base
        val configuration = Configuration()
        configuration.setLocale(Locale.forLanguageTag(selected))
        return base.createConfigurationContext(configuration)
    }

    fun text(context: Context, value: String): String {
        if (effective(context) != CHINESE || value.isBlank()) return value
        translations[value]?.let { return it }
        return when {
            value.matches(Regex("\\d+ matching shots")) -> value.substringBefore(' ') + " 个匹配拍法"
            value.startsWith("Timer ") && value.endsWith("s") -> "定时 ${value.removePrefix("Timer ")}"
            value.startsWith("Opening ") && value.endsWith(" camera…") -> "正在打开${if (value.contains("front")) "前置" else "后置"}相机…"
            value.startsWith("Pose: ") -> "姿势：" + value.removePrefix("Pose: ")
            value.startsWith("Light: ") -> "光线：" + value.removePrefix("Light: ")
            value.startsWith("How: ") -> "动作：" + value.removePrefix("How: ")
            value.startsWith("Guide: ") -> "辅助线：" + if (value.endsWith("On")) "开" else "关"
            value.startsWith("Grid: ") -> "网格：" + if (value.endsWith("On")) "开" else "关"
            value.startsWith("Timer: ") -> "定时：" + value.removePrefix("Timer: ").replace("Off", "关")
            value.startsWith("Share to unlock ") -> "分享后解锁" + value.removePrefix("Share to unlock ")
            value.startsWith("Share once to unlock ") -> "分享一次，解锁" + value.removePrefix("Share once to unlock ")
            value.startsWith("All ") && value.endsWith(" shots are now unlocked") -> "已解锁全部拍法"
            value.startsWith("The ") && value.contains(" camera is unavailable") -> "该相机不可用，已切回可用镜头。"
            value.startsWith("Exposure change failed") -> "曝光调整失败"
            value.startsWith("Zoom change failed") -> "变焦失败"
            else -> value
        }
    }

    private val translations = mapOf(
        "Language" to "语言",
        "Follow system" to "跟随系统",
        "Simplified Chinese" to "简体中文",
        "English" to "English",
        "More" to "更多",
        "Import a reference photo" to "导入参考照片",
        "Open my reference" to "打开我的参考图",
        "Send feedback" to "发送反馈",
        "Cancel" to "取消",
        "Choose your scene" to "选择场景",
        "Start with where you are" to "从你所在的地方开始",
        "How do you want to shoot?" to "你想怎么拍？",
        "⌕   Find seaside, cafe, park..." to "⌕   搜索海边、咖啡店、公园……",
        "CONTINUE" to "继续",
        "Return to your last setup" to "继续上次拍摄",
        "Your camera settings are kept" to "已保留上次相机设置",
        "YOUR FIRST SHOT · ABOUT 1 MIN" to "第一次跟拍 · 约 1 分钟",
        "Follow one simple template for your first photo" to "先跟着一个简单模板完成第一张",
        "① Choose  ② Align  ③ Shoot" to "① 选择  ② 对齐  ③ 拍摄",
        "Start" to "开始",
        "Where are you now?" to "你现在在哪里？",
        "Choose your surroundings and see repeatable shots" to "选择周围环境，查看可以复刻的拍法",
        "Easy solo shots" to "简单单人拍法",
        "Start with the action that feels easiest" to "先从最容易完成的动作开始",
        "Popular this week" to "本周热门",
        "Templates you can recreate in real places" to "在真实场景中可以复刻的模板",
        "＋ Import" to "＋ 导入",
        "My reference" to "我的参考图",
        "Reference mode" to "参考模式",
        "Lines" to "线稿",
        "Line guide" to "线稿参考",
        "Original" to "原图",
        "Original photo" to "半透明原图",
        "Hide reference" to "关闭参考",
        "Show reference" to "显示参考",
        "Hide" to "隐藏",
        "Show" to "显示",
        "Ref. off" to "参考已关",
        "Reference opacity" to "参考浓度",
        "View full photo" to "查看完整原图",
        "Line guide ready · choose it in Reference" to "线稿已就绪，可在参考模式中切换",
        "Line guide unavailable · use the original or retry from your reference" to "线稿暂不可用，可使用原图或返回参考页重试",
        "Generating line guide" to "正在生成线稿",
        "Line guide complete · tap to view" to "线稿已完成 · 点击查看",
        "Line guide complete · tap the status bar to view" to "线稿已完成，可点击状态栏查看",
        "Line guide failed · tap to retry" to "线稿生成失败 · 点击重试",
        "Feedback" to "反馈",
        "← Back" to "← 返回",
        "‹ Back" to "‹ 返回",
        "Scene Pack" to "场景拍法",
        "All" to "全部",
        "Standing" to "站姿",
        "Sitting" to "坐姿",
        "Walking" to "走动",
        "Lying" to "躺姿",
        "Natural" to "自然",
        "Landscape" to "横拍",
        "Portrait" to "竖拍",
        "No templates match this combination yet.\nTry another pose or scene." to "暂时没有匹配的模板。\n换一个姿势或场景试试。",
        "Share to unlock" to "分享后解锁",
        "Share once\nto unlock this scene" to "分享一次\n解锁当前场景",
        "Shot" to "拍法",
        "After shooting, your most recent photo will appear here" to "完成拍摄后，最近使用的拍法会显示在这里",
        "Your reference photo" to "你的参考照片",
        "Saved on this device" to "已保存在本机",
        "Generate a guide from your saved photo" to "为保存的照片生成参考线稿",
        "You can go back while this finishes." to "生成期间可以先返回其他页面。",
        "Compare with original" to "对比原图",
        "Shoot with this guide" to "使用此线稿拍摄",
        "Regenerate guide" to "重新生成线稿",
        "Generate / retry" to "生成／重试",
        "Shoot with original" to "用半透明原图拍摄",
        "Choose another photo" to "选择另一张照片",
        "Preparing your guide…" to "正在准备参考线稿……",
        "Reading your photo…" to "正在读取照片……",
        "Finding the subject…" to "正在识别人物……",
        "Drawing fine lines on this device…" to "正在手机本地生成细线……",
        "Finishing the line guide…" to "正在合成线稿……",
        "Saving the line guide…" to "正在保存线稿……",
        "Line guide ready · saved on this device" to "线稿已生成 · 保存在本机",
        "Could not finish. Retry, or use the original photo." to "生成未完成，请重试或使用原图。",
        "Not enough memory. Close other apps and retry, or use the original." to "内存不足，请关闭其他应用后重试，或使用原图。",
        "SHOTGUIDE · YOUR FIRST SHOT" to "SHOTGUIDE · 第一次跟拍",
        "Take a better photo\non your first try" to "第一次尝试\n也能拍得更好",
        "Choose a reference, align the position and press the shutter. No photography lessons or perfect match required." to "选择参考图，对齐位置后按下快门。不需要摄影基础，也不用完全一致。",
        "Finish your first shot in about 1 minute" to "大约 1 分钟完成第一次跟拍",
        "Choose a look" to "选择喜欢的拍法",
        "Start with any template that feels easy" to "先选一个容易完成的模板",
        "Align with the guide" to "对齐辅助线",
        "Move the phone to match subject position and scale" to "移动手机，让人物位置和大小接近参考",
        "Share only if you want" to "按需要选择分享",
        "Your original saves normally; the shot card is optional" to "原图会正常保存，分享卡片完全自愿",
        "Take my first shot" to "开始第一次跟拍",
        "Browse all templates" to "浏览全部模板",
        "Guide" to "线稿",
        "Photo" to "原图",
        "Lens" to "镜头",
        "Pose" to "姿势",
        "Frame" to "画幅",
        "Est. time" to "预计用时",
        "30 sec" to "30 秒",
        "Follow these three steps" to "按照这三步拍摄",
        "Start camera" to "打开相机",
        "Reference" to "参考图",
        "Timer off" to "定时关闭",
        "Light tips" to "光线建议",
        "Shooting tips" to "拍摄建议",
        "Got it" to "知道了",
        "Find soft light, turn the face toward it, tap the face to focus, and use exposure compensation if needed." to "寻找柔和光线，让脸朝向亮处，点击面部对焦，必要时调整曝光。",
        "Find soft light, turn the face toward it and tap the subject to focus." to "寻找柔和光线，让脸朝向亮处并点击人物对焦。",
        "Flash loading" to "正在读取闪光灯",
        "⚡ On" to "闪光开",
        "⚡ Off" to "闪光关",
        "Capture flash on" to "拍照闪光灯已开启",
        "Capture flash off" to "拍照闪光灯已关闭",
        "Flash unavailable" to "当前镜头不支持闪光灯",
        "Torch on" to "常亮补光：开",
        "Torch off" to "常亮补光：关",
        "Could not change continuous light" to "无法切换常亮补光",
        "Tap the preview to focus" to "点击取景画面对焦",
        "Switch camera" to "切换前后摄像头",
        "Take photo" to "拍照",
        "Zoom" to "变焦",
        "Zoom level" to "变焦倍率",
        "Zoom loading" to "正在读取变焦",
        "Exposure loading" to "正在读取曝光",
        "Hold for photo" to "按住查看原图",
        "Guide opacity" to "辅助线浓度",
        "Grid" to "网格",
        "Done" to "完成",
        "Black / white" to "黑色／白色",
        "More camera settings" to "更多相机设置",
        "Guide opacity and color" to "辅助线浓度与颜色",
        "Exposure compensation" to "曝光补偿",
        "Flash" to "补光",
        "Mirror on" to "镜像开启",
        "Mirror off" to "镜像关闭",
        "Flash on" to "补光开启",
        "Flash off" to "补光关闭",
        "No flash" to "不支持补光",
        "Exposure failed" to "曝光调整失败",
        "Exposure unavailable" to "曝光不可用",
        "Your first guided shot" to "第一次跟拍",
        "① Align position   ② Match scale   ③ Shoot when close" to "① 对齐位置   ② 调整大小   ③ 接近后拍摄",
        "Switch to the front camera for selfies; use a 3s or 10s timer when you need distance." to "自拍时切换前置镜头；需要距离时使用 3 秒或 10 秒定时。",
        "Camera could not start\nTap to retry, or go back and check camera permission" to "相机无法启动\n点击重试，或返回检查相机权限",
        "Camera permission is required. Tap a shot again to retry, or enable permission in system settings." to "需要相机权限。请重新进入拍法，或在系统设置中授权。",
        "Processing photo, please wait" to "正在处理照片，请稍候",
        "Your photo" to "你的照片",
        "Loading your photo…" to "正在加载照片……",
        "Side by side" to "并排对比",
        "Save to gallery" to "保存到相册",
        "Saved to gallery" to "已保存到相册",
        "Send this shot idea to a friend" to "把这个拍法发给朋友",
        "Creates a separate share card without changing your original photo." to "生成单独的分享卡，不会修改原始照片。",
        "Create shot card" to "生成拍法卡片",
        "Share this shot idea" to "分享拍法卡片",
        "Custom reference" to "自选参考",
        "I took this photo in ShotGuide using my own reference." to "这是我用 ShotGuide 跟着自选参考拍的照片。",
        "I recreated" to "我复刻了",
        "Template" to "模板",
        "Keep settings and shoot again" to "保留设置并继续拍摄",
        "Rate this photo (optional)" to "评价这张照片（可选）",
        "How does it compare?" to "和参考图相比怎么样？",
        "Composition is close" to "构图接近",
        "Pose is close" to "姿势接近",
        "Lighting is off" to "光线不合适",
        "I want to retake" to "我想重拍",
        "Saving…" to "正在保存……",
        "Retry save" to "重试保存",
        "Saved to Pictures/ShotGuide" to "已保存到相册 ShotGuide",
        "Recorded. This does not affect saving." to "已记录，不影响照片保存。",
        "Shooting reference" to "拍摄参考",
        "Borrow the pose and composition; the surroundings do not need to match exactly." to "参考姿势和构图即可，周围环境不需要完全一致。",
        "Keep shooting" to "继续拍摄",
        "Close" to "关闭",
        "Copy log" to "复制记录"
    )
}
