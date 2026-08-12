package com.inscreen.mic

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.Locale

internal class ModuleSpeechController(
    private val context: Context,
    private val emit: (String) -> Unit,
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private val accumulator = SpeechTextAccumulator()
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var stopping = false
    private var usingOnDevice = false
    private var onDeviceFailed = false
    private var limitStop = false
    private var restartRunnable: Runnable? = null
    private var finishRunnable: Runnable? = null
    private var limitRunnable: Runnable? = null

    fun status(permissionGranted: Boolean): String = JSONObject()
        .put("ok", true)
        .put("permiso", permissionGranted)
        .put("onDevice", onDeviceAvailable())
        .put("servicioSistema", SpeechRecognizer.isRecognitionAvailable(context))
        .put("idioma", Locale.getDefault().toLanguageTag())
        .put("activo", active)
        .toString()

    fun start(allowSystemRecognizer: Boolean): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active) return failure("voice_busy")
        val localAvailable = onDeviceAvailable()
        val decision = SpeechRecognitionPolicy.decide(
            permissionGranted = true,
            onDeviceAvailable = localAvailable,
            systemAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            allowSystemRecognizer = allowSystemRecognizer,
        )
        if (decision == SpeechRecognitionPolicy.Decision.CONSENT_REQUIRED) return failure("system_recognizer_consent_required")
        if (decision == SpeechRecognitionPolicy.Decision.UNAVAILABLE) return failure("recognizer_unavailable")

        return runCatching {
            usingOnDevice = decision == SpeechRecognitionPolicy.Decision.ON_DEVICE
            recognizer = if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }.also { it.setRecognitionListener(this) }
            accumulator.reset()
            active = true
            stopping = false
            limitStop = false
            scheduleLimit()
            listen()
            event("escuchando", "")
            JSONObject().put("ok", true).put("onDevice", usingOnDevice)
                .put("idioma", Locale.getDefault().toLanguageTag()).toString()
        }.getOrElse {
            release()
            failure("recognizer_start_failed")
        }
    }

    fun stop(): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!active) return failure("voice_not_active")
        if (!stopping) {
            stopping = true
            limitStop = false
            limitRunnable?.let(handler::removeCallbacks)
            limitRunnable = null
            restartRunnable?.let(handler::removeCallbacks)
            restartRunnable = null
            runCatching { recognizer?.stopListening() }
            scheduleForcedFinish()
        }
        return JSONObject().put("ok", true).toString()
    }

    fun cancel(): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active) runCatching { recognizer?.cancel() }
        release()
        return JSONObject().put("ok", true).toString()
    }

    fun stopForBackground() {
        if (!active) return
        val text = accumulator.text()
        runCatching { recognizer?.cancel() }
        release()
        event("error", text, "voice_interrupted")
    }

    fun destroy() {
        if (Looper.myLooper() == Looper.getMainLooper()) cancel()
        else handler.post(::cancel)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(partialResults: Bundle?) {
        if (!active || stopping) return
        val partial = bestResult(partialResults)
        if (partial.isNotBlank()) event("parcial", accumulator.updatePartial(partial))
    }

    override fun onResults(results: Bundle?) {
        if (!active) return
        val text = accumulator.commit(bestResult(results))
        if (stopping) finish(text, limitReached = limitStop)
        else {
            event("parcial", text)
            scheduleRestart()
        }
    }

    override fun onError(error: Int) {
        if (!active) return
        if (stopping) {
            finish(accumulator.commitPartial(), limitReached = limitStop)
            return
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            accumulator.commitPartial()
            scheduleRestart()
            return
        }
        val text = accumulator.text()
        val code = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "recognizer_audio_error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone_permission_denied"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "recognizer_network_error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "recognizer_server_error"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "recognizer_language_unavailable"
            else -> "recognizer_error"
        }
        if (usingOnDevice && code == "recognizer_language_unavailable") onDeviceFailed = true
        release()
        event("error", text, code)
    }

    private fun listen() {
        if (!active || stopping) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDevice)
        recognizer?.startListening(intent)
    }

    private fun scheduleRestart() {
        restartRunnable?.let(handler::removeCallbacks)
        val task = Runnable {
            restartRunnable = null
            if (!active || stopping) return@Runnable
            runCatching { listen() }.onFailure {
                val text = accumulator.text()
                release()
                event("error", text, "recognizer_restart_failed")
            }
        }
        restartRunnable = task
        handler.postDelayed(task, RESTART_DELAY_MS)
    }

    private fun scheduleLimit() {
        val task = Runnable {
            if (!active) return@Runnable
            stopping = true
            limitStop = true
            accumulator.commitPartial()
            runCatching { recognizer?.stopListening() }
            scheduleForcedFinish(limitReached = true)
        }
        limitRunnable = task
        handler.postDelayed(task, MAX_SESSION_MS)
    }

    private fun scheduleForcedFinish(limitReached: Boolean = false) {
        finishRunnable?.let(handler::removeCallbacks)
        val task = Runnable {
            if (active && stopping) finish(accumulator.commitPartial(), limitReached)
        }
        finishRunnable = task
        handler.postDelayed(task, STOP_GRACE_MS)
    }

    private fun finish(text: String, limitReached: Boolean) {
        val finalText = text.trim()
        release()
        event("final", finalText, if (limitReached) "voice_time_limit" else null)
    }

    private fun release() {
        restartRunnable?.let(handler::removeCallbacks)
        finishRunnable?.let(handler::removeCallbacks)
        limitRunnable?.let(handler::removeCallbacks)
        restartRunnable = null
        finishRunnable = null
        limitRunnable = null
        runCatching { recognizer?.destroy() }
        recognizer = null
        active = false
        stopping = false
        usingOnDevice = false
        limitStop = false
    }

    private fun event(state: String, text: String, error: String? = null) {
        val payload = JSONObject().put("estado", state).put("texto", text)
        if (error != null) payload.put("error", error)
        emit(payload.toString())
    }

    private fun bestResult(bundle: Bundle?): String = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull().orEmpty()

    private fun onDeviceAvailable() = !onDeviceFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun failure(code: String) = JSONObject().put("ok", false).put("error", code).toString()

    companion object {
        private const val RESTART_DELAY_MS = 180L
        private const val STOP_GRACE_MS = 1_500L
        private const val MAX_SESSION_MS = 5 * 60 * 1_000L
    }
}
