package dev.sharptalons.obsdirector.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Hold to speak a note; release and the phone's speech recognizer turns it into text.
 * [onStatus] gets short status lines ("Listening…", "Voice note turned into text").
 */
@Composable
fun VoiceButton(onText: (String) -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    var active by remember { mutableStateOf(false) }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember { if (available) SpeechRecognizer.createSpeechRecognizer(context) else null }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onStatus(if (ok) "Microphone allowed. Hold the mic to speak." else "Voice notes need the microphone permission.")
    }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }

    fun listen() {
        val r = recognizer ?: run { onStatus("Speech recognition isn't available on this phone."); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onStatus("Listening… release to finish") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { active = false; onStatus("Didn't catch that. Hold the mic and try again.") }
            override fun onResults(results: Bundle?) {
                active = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) { onText(text); onStatus("Voice note turned into text. Edit it if a word is off.") }
                else onStatus("Didn't catch that. Hold the mic and try again.")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        })
        active = true
    }

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) T.negative else Color.Transparent)
            .border(1.dp, if (active) T.negative else T.control, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    listen()
                    waitForUpOrCancellation()
                    if (active) recognizer?.stopListening()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Mic, "Hold to speak a note", tint = T.text, modifier = Modifier.size(18.dp)) }
}
