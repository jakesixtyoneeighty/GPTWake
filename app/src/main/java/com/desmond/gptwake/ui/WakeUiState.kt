package com.desmond.gptwake.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.desmond.gptwake.AudioProbe
import com.desmond.gptwake.Cfg
import com.desmond.gptwake.L
import com.desmond.gptwake.WakeController
import com.desmond.gptwake.WakeService
import kotlinx.coroutines.delay

/**
 * Everything the screen needs from the engine.
 *
 * The engine is plain Java with volatile fields and no change notification, so this is polled. That
 * is what the old View code did too, but `produceState` only triggers recomposition when a value
 * actually changes — the previous `Handler` loop re-ran the entire render, including re-inflating
 * the permission checklist, 1.4 times a second regardless.
 */
data class WakeUiState(
    val state: WakeController.State?,
    val foreground: Boolean,
    val micRunning: Boolean,
    val counters: String,
    val events: List<String>,
    val micSilenced: Boolean = false,
    val micDead: Boolean = false,
    val audioSource: String = "",
)

/** Which setup step is still outstanding. Ordered; the banner offers the first unsatisfied one. */
enum class SetupStep { MIC, NOTIFICATIONS, OVERLAY, ASSISTANT, DONE }

data class Permissions(
    val mic: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
    val assistant: Boolean,
) {
    val next: SetupStep
        get() = when {
            !mic -> SetupStep.MIC
            !notifications -> SetupStep.NOTIFICATIONS
            !overlay -> SetupStep.OVERLAY
            !assistant -> SetupStep.ASSISTANT
            else -> SetupStep.DONE
        }

    fun granted(step: SetupStep): Boolean = when (step) {
        SetupStep.MIC -> mic
        SetupStep.NOTIFICATIONS -> notifications
        SetupStep.OVERLAY -> overlay
        SetupStep.ASSISTANT -> assistant
        SetupStep.DONE -> true
    }
}

fun readPermissions(context: Context) = Permissions(
    mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED,
    notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED,
    overlay = Settings.canDrawOverlays(context),
    // ChatGPT must hold the assistant role or it cannot record under keyguard. This app must never
    // take that role for itself.
    assistant = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        ?.startsWith("com.openai.chatgpt") == true,
)

/** Engine state. 700ms matches the old refresh cadence and is plenty for text that reads as prose. */
@Composable
fun rememberWakeUiState(): State<WakeUiState> = produceState(
    initialValue = WakeUiState(null, false, false, "", emptyList())
) {
    while (true) {
        val controller = WakeService.controller()
        value = WakeUiState(
            state = controller?.state(),
            foreground = WakeService.isForegroundNow(),
            micRunning = AudioProbe.isRunning(),
            counters = controller?.counters().orEmpty(),
            events = recentEvents(),
            micSilenced = AudioProbe.isSilenced(),
            micDead = AudioProbe.isHearingSilence(),
            audioSource = if (AudioProbe.isRunning()) AudioProbe.activeSourceName() else "",
        )
        delay(700)
    }
}

/**
 * Permissions change only when the user comes back from a Settings screen, so this polls slowly.
 * The old code spent four binder calls every 700ms re-deriving it.
 */
@Composable
fun rememberPermissions(context: Context): State<Permissions> = produceState(
    initialValue = readPermissions(context), context
) {
    while (true) {
        value = readPermissions(context)
        delay(1500)
    }
}

/**
 * Smoothed microphone level, 0..1. Polled fast because this drives the indicator and should feel
 * immediate; each tick is one volatile read plus an exponential smoothing step.
 */
@Composable
fun rememberMicLevel(active: Boolean): State<Float> = produceState(0f, active) {
    if (!active) {
        value = 0f
        return@produceState
    }
    var smoothed = 0f
    while (true) {
        // The historical UI mapped rms/20 onto 0..100, i.e. full scale at rms 2000.
        val raw = (AudioProbe.lastRms() / 2000.0).toFloat().coerceIn(0f, 1f)
        // Rise fast so a spoken word registers, fall slowly so it does not flicker.
        smoothed = if (raw > smoothed) raw else smoothed + (raw - smoothed) * 0.25f
        value = smoothed
        delay(80)
    }
}

/** The tail of the in-memory log, minus the periodic instrumentation lines. */
private fun recentEvents(limit: Int = 40): List<String> =
    L.dump().lineSequence()
        .filter { it.isNotEmpty() }
        .filterNot { "KWS_STATS" in it || "CONFIGS" in it || "POWER" in it }
        .toList()
        .takeLast(limit)

/** Collapses the engine's ten states onto the seven the indicator distinguishes. */
fun WakeController.State?.toIndicatorMode(foreground: Boolean): IndicatorMode = when (this) {
    WakeController.State.KWS_LISTENING -> IndicatorMode.LISTENING
    WakeController.State.STARTING,
    WakeController.State.KWS_MODEL_LOADING -> IndicatorMode.LOADING

    WakeController.State.MIC_HANDOFF,
    WakeController.State.CHATGPT_LAUNCHING -> IndicatorMode.LAUNCHING

    WakeController.State.VOICE_ACTIVE -> IndicatorMode.VOICE
    WakeController.State.KWS_REACQUIRING -> IndicatorMode.LOADING
    WakeController.State.EXTERNAL_COMMUNICATION -> IndicatorMode.PAUSED
    WakeController.State.ERROR -> IndicatorMode.ERROR
    WakeController.State.STOPPED -> IndicatorMode.IDLE
    null -> if (foreground) IndicatorMode.LOADING else IndicatorMode.IDLE
}

/** True when the engine is in a state where the microphone is actually feeding the model. */
fun WakeController.State?.isLive(): Boolean =
    this == WakeController.State.KWS_LISTENING || this == WakeController.State.VOICE_ACTIVE

/** Eval mode is a plain volatile Java field; reading it needs no snapshot plumbing. */
fun evalMode(): Boolean = Cfg.evalMode
