package com.deeplex.earpage.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.deeplex.earpage.bridge.BridgeException
import com.deeplex.earpage.bridge.BridgeHost
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Native voice capture. Audio stays in the app's cache and is played back by
 * the host; the page only receives descriptive metadata.
 */
class RecorderCapability(
    private val context: Context,
    private val bridge: BridgeHost,
    private val launchPermissionRequest: () -> Unit,
) {
    private data class RecordingResult(
        val sessionId: String,
        val file: File,
        val durationMs: Long,
        val mimeType: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("sessionId", sessionId)
            .put("uri", file.toURI().toString())
            .put("durationMs", durationMs)
            .put("mimeType", mimeType)
            .put("sizeBytes", file.length())
    }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("earpage", Context.MODE_PRIVATE)

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var playingSessionId: String? = null
    private var sessionId: String? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var levelTask: Runnable? = null
    private var lastRecording: RecordingResult? = null
    private var permissionContinuation: ((Boolean) -> Unit)? = null

    fun register() {
        bridge.register("recorder.isAvailable") { availability() }
        bridge.registerAsync("recorder.requestPermission") { _, complete -> requestPermission(complete) }
        bridge.register("recorder.start") { params -> start(params) }
        bridge.register("recorder.stop") { params -> stop(params) }
        bridge.register("recorder.cancel") { _ -> cancel() }
        bridge.register("recorder.play") { params -> play(params) }
        bridge.register("recorder.stopPlayback") { _ -> stopPlayback() }
    }

    fun onPermissionResult(granted: Boolean) {
        preferences.edit().putBoolean(KEY_REQUESTED, true).apply()
        val continuation = permissionContinuation
        permissionContinuation = null
        continuation?.invoke(granted)
    }

    private fun availability(): JSONObject {
        val permission = when {
            hasPermission() -> "granted"
            preferences.getBoolean(KEY_REQUESTED, false) -> "denied"
            else -> "undetermined"
        }
        return JSONObject().put("available", true).put("permission", permission)
    }

    private fun requestPermission(complete: (Any?, BridgeException?) -> Unit) {
        if (hasPermission()) {
            complete(JSONObject().put("permission", "granted"), null)
            return
        }
        if (permissionContinuation != null) {
            complete(null, BridgeException("PERMISSION_REQUEST_IN_PROGRESS", "A permission request is already pending"))
            return
        }
        permissionContinuation = { granted ->
            complete(
                JSONObject().put("permission", if (granted) "granted" else "denied"),
                null,
            )
        }
        launchPermissionRequest()
    }

    private fun start(params: JSONObject?): JSONObject {
        if (!hasPermission()) {
            throw BridgeException("PERMISSION_DENIED", "Microphone permission has not been granted")
        }
        if (recorder != null) {
            throw BridgeException("RECORDING_ACTIVE", "A recording is already in progress")
        }

        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File(directory, "$id.m4a")
        val maxDurationMs = (params?.optLong("maxDurationMs", DEFAULT_MAX_DURATION_MS) ?: DEFAULT_MAX_DURATION_MS)
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        val media = createRecorder()
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(128_000)
            media.setAudioSamplingRate(44_100)
            media.setOutputFile(file.absolutePath)
            media.setMaxDuration(maxDurationMs.toInt())
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.i(TAG, "recording reached max duration")
                    finishAtMaxDuration()
                }
            }
            media.prepare()
            media.start()
        } catch (error: Exception) {
            runCatching { media.release() }
            runCatching { file.delete() }
            throw BridgeException("RECORD_FAILED", error.message ?: "Could not start recording")
        }

        recorder = media
        sessionId = id
        outputFile = file
        startedAt = SystemClock.elapsedRealtime()
        lastRecording = null
        startLevelUpdates(id)
        Log.i(TAG, "recording started id=$id max=${maxDurationMs}ms")
        return JSONObject().put("sessionId", id)
    }

    private fun stop(params: JSONObject?): JSONObject {
        if (recorder == null) {
            val completed = lastRecording
                ?: throw BridgeException("NO_ACTIVE_RECORDING", "No recording is in progress")
            return completed.toJson()
        }
        val wanted = params?.optString("sessionId", "") ?: ""
        if (wanted.isNotEmpty() && wanted != sessionId) {
            throw BridgeException("NO_ACTIVE_RECORDING", "No recording with sessionId $wanted")
        }
        return stopInternal().toJson()
    }

    private fun cancel(): JSONObject {
        val media = recorder
        stopLevelUpdates()
        recorder = null
        if (media != null) {
            runCatching { media.stop() }
            runCatching { media.release() }
        }
        outputFile?.delete()
        outputFile = null
        sessionId = null
        lastRecording = null
        Log.i(TAG, "recording cancelled")
        return JSONObject().put("cancelled", true)
    }

    private fun stopInternal(): RecordingResult {
        val media = recorder ?: throw BridgeException("NO_ACTIVE_RECORDING", "No recording is in progress")
        val file = outputFile
        val id = sessionId
        val durationMs = SystemClock.elapsedRealtime() - startedAt

        stopLevelUpdates()
        recorder = null

        try {
            media.stop()
        } catch (error: RuntimeException) {
            Log.w(TAG, "recorder stop failed", error)
            runCatching { media.release() }
            file?.delete()
            throw BridgeException("RECORDING_TOO_SHORT", "The recording was too short to save")
        }
        runCatching { media.release() }

        if (file == null || id == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            throw BridgeException("RECORD_FAILED", "The recording produced no audio")
        }

        val result = RecordingResult(id, file, durationMs, "audio/mp4")
        lastRecording = result
        Log.i(TAG, "recording stopped id=$id duration=${durationMs}ms")
        return result
    }

    private fun finishAtMaxDuration() {
        val result = runCatching { stopInternal() }.getOrNull() ?: return
        bridge.emit("recorder.finished", result.toJson())
    }

    private fun play(params: JSONObject?): JSONObject {
        val wanted = params?.optString("sessionId", "") ?: ""
        val recording = lastRecording
            ?: throw BridgeException("NO_RECORDING", "There is no completed recording to play")
        if (wanted.isNotEmpty() && wanted != recording.sessionId) {
            throw BridgeException("NO_RECORDING", "No recording with sessionId $wanted")
        }
        if (!recording.file.exists()) {
            throw BridgeException("NO_RECORDING", "The recording file is missing")
        }

        stopPlayback()
        val media = MediaPlayer()
        try {
            media.setDataSource(recording.file.absolutePath)
            media.setOnCompletionListener {
                bridge.emit(
                    "recorder.playback.ended",
                    JSONObject().put("sessionId", recording.sessionId),
                )
                runCatching { it.release() }
                if (player === it) player = null
            }
            media.prepare()
            media.start()
        } catch (error: Exception) {
            runCatching { media.release() }
            throw BridgeException("PLAYBACK_FAILED", error.message ?: "Could not play the recording")
        }
        player = media
        playingSessionId = recording.sessionId
        return JSONObject().put("playing", true).put("sessionId", recording.sessionId)
    }

    private fun stopPlayback(): JSONObject {
        val media = player
        player = null
        playingSessionId = null
        if (media != null) {
            runCatching { media.stop() }
            runCatching { media.release() }
        }
        return JSONObject().put("stopped", true)
    }

    private fun startLevelUpdates(id: String) {
        stopLevelUpdates()
        val task = object : Runnable {
            override fun run() {
                val media = recorder ?: return
                val amplitude = runCatching { media.maxAmplitude }.getOrDefault(0)
                bridge.emit(
                    "recorder.level",
                    JSONObject().put("sessionId", id).put("rms", amplitude / 32767.0),
                )
                handler.postDelayed(this, LEVEL_INTERVAL_MS)
            }
        }
        levelTask = task
        handler.post(task)
    }

    private fun stopLevelUpdates() {
        levelTask?.let { handler.removeCallbacks(it) }
        levelTask = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private companion object {
        const val TAG = "EARPAGE"
        const val KEY_REQUESTED = "mic_requested"
        const val LEVEL_INTERVAL_MS = 100L
        const val DEFAULT_MAX_DURATION_MS = 30_000L
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 300_000L
    }
}
