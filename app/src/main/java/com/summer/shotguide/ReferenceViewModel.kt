package com.summer.shotguide

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class ReferenceState(
    val working: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
    val completed: Boolean = false,
    val readingPhoto: Boolean = false,
)

class ReferenceViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableLiveData(ReferenceState())
    private val executor = Executors.newSingleThreadExecutor()
    val photoFile = File(app.filesDir, "reference.jpg")
    val linesFile = File(app.filesDir, "reference-lines-composition.png")
    private var automaticAttempted = false
    private var completionAnnounced = false
    private var completionSeen = false

    fun importPhoto(uri: Uri) = start(ReferenceState(true, 5, "Reading your photo…", readingPhoto = true)) {
        automaticAttempted = true
        completionSeen = false
        completionAnnounced = false
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f,1600f/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*scale).roundToInt().coerceAtLeast(1),(info.size.height*scale).roundToInt().coerceAtLeast(1))
        }
        try {
            // Invalidate old lines before replacing the source, including process-death cases.
            AtomicFile(linesFile).delete()
            save(bitmap, photoFile, Bitmap.CompressFormat.JPEG)
            state.postValue(ReferenceState(true, 15, "Preparing your guide…"))
            generate(bitmap)
        } finally { bitmap.recycle() }
    }

    fun regenerate() = start {
        automaticAttempted = true
        completionSeen = false
        completionAnnounced = false
        val bitmap = BitmapFactory.decodeFile(photoFile.path) ?: error("Reference missing")
        try { generate(bitmap) } finally { bitmap.recycle() }
    }

    fun generateMissingOnce(): Boolean {
        if (automaticAttempted || state.value?.working == true || !photoFile.exists() || hasValidLines()) return false
        automaticAttempted = true
        regenerate()
        return true
    }

    fun hasValidLines(): Boolean {
        if (!linesFile.isFile || linesFile.length() == 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(linesFile.path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    fun shouldShowCompleted(): Boolean = state.value?.completed == true && !completionSeen
    fun markCompletionSeen() { completionSeen = true }
    fun consumeCompletionAnnouncement(): Boolean {
        if (state.value?.completed != true || completionAnnounced) return false
        completionAnnounced = true
        return true
    }

    private fun start(initial: ReferenceState = ReferenceState(true, 2, "Preparing your guide…"), task: () -> Unit) {
        if (state.value?.working == true) return
        state.value = initial
        executor.execute {
            try {
                task()
                state.postValue(ReferenceState(false, 100, "Line guide ready · saved on this device", true))
            } catch (error: Exception) {
                Log.e("LocalLineart", "Reference processing failed", error)
                state.postValue(ReferenceState(false, 0, "Could not finish. Retry, or use the original photo."))
            } catch (error: OutOfMemoryError) {
                Log.e("LocalLineart", "Insufficient memory", error)
                state.postValue(ReferenceState(false, 0, "Not enough memory. Close other apps and retry, or use the original."))
            }
        }
    }

    private fun generate(bitmap: Bitmap) {
        val result = LocalLineart(getApplication()).generate(bitmap) { value, message -> state.postValue(ReferenceState(true, value, message)) }
        try {
            state.postValue(ReferenceState(true, 95, "Saving the line guide…"))
            save(result,linesFile,Bitmap.CompressFormat.PNG)
        } finally { result.recycle() }
    }

    private fun save(bitmap: Bitmap, destination: File, format: Bitmap.CompressFormat) {
        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
            check(bitmap.compress(format,95,stream))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    override fun onCleared() { executor.shutdown() }
}
