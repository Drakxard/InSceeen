package com.inscreen.mic

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import androidx.core.app.NotificationManagerCompat

class MediaSessionObserver(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (String) -> Unit,
) {
    data class CommandResult(
        val ok: Boolean,
        val error: String = "",
        val expectedState: String? = null,
    )

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaAccessService::class.java)
    private var controllers: List<MediaController> = emptyList()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var started = false
    private var lastState = "none"

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        replaceControllers(it.orEmpty(), notify = true)
    }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun start(): String {
        stop()
        if (!hasAccess()) {
            lastState = "none"
            return lastState
        }
        return try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
            started = true
            replaceControllers(manager.getActiveSessions(listenerComponent), notify = false)
            lastState
        } catch (_: SecurityException) {
            stop()
            lastState = "none"
            lastState
        }
    }

    fun stop() {
        if (started) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        started = false
        callbacks.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        callbacks.clear()
        controllers = emptyList()
    }

    fun state(): String {
        if (!hasAccess()) return "none"
        refreshState(notify = false)
        return lastState
    }

    fun execute(action: String): CommandResult {
        if (!hasAccess()) return CommandResult(false, "Habilita Control multimedia en InScreen Mic.")
        val currentControllers = runCatching { manager.getActiveSessions(listenerComponent) }
            .getOrElse { return CommandResult(false, "Android no permitió consultar las sesiones multimedia.") }
        replaceControllers(currentControllers, notify = false)
        val decision = MediaStatePolicy.decide(action, controllers.map(::playbackState))
        if (!decision.ok) return CommandResult(false, decision.error)
        if (decision.controllerIndex >= 0) {
            val controls = controllers[decision.controllerIndex].transportControls
            if (decision.command == "pause") controls.pause()
            if (decision.command == "play") controls.play()
        }
        val expected = when (decision.command) {
            "pause" -> "paused"
            "play" -> "playing"
            else -> lastState
        }
        return CommandResult(true, expectedState = expected)
    }

    fun refreshAndNotify(): String {
        if (!hasAccess()) {
            updateState("none", notify = true)
            return lastState
        }
        runCatching { replaceControllers(manager.getActiveSessions(listenerComponent), notify = true) }
        return lastState
    }

    private fun replaceControllers(newControllers: List<MediaController>, notify: Boolean) {
        callbacks.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        callbacks.clear()
        controllers = newControllers
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    refreshState(notify = true)
                }
                override fun onSessionDestroyed() {
                    runCatching {
                        replaceControllers(manager.getActiveSessions(listenerComponent), notify = true)
                    }
                }
            }
            callbacks[controller] = callback
            controller.registerCallback(callback, handler)
        }
        refreshState(notify)
    }

    private fun refreshState(notify: Boolean) {
        val state = MediaStatePolicy.aggregate(controllers.map(::playbackState))
        updateState(state, notify)
    }

    private fun updateState(state: String, notify: Boolean) {
        if (state == lastState) return
        lastState = state
        if (notify) onStateChanged(state)
    }

    private fun playbackState(controller: MediaController): String = when (controller.playbackState?.state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        else -> "none"
    }
}
