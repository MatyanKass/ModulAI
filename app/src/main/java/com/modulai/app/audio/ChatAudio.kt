package com.modulai.app.audio

import android.content.Context
import com.modulai.app.R
import com.modulai.core.agent.AgentLog
import com.modulai.core.agent.LogKind
import com.modulai.core.agent.Tool
import com.modulai.core.runtime.SpeechException
import com.modulai.core.runtime.ToolSpec
import org.json.JSONObject

/**
 * Voice input for a microphone button: listens until the speaker pauses (or [AudioHub.stopListening]
 * is called, or 30 seconds pass) and returns the recognized text. The RECORD_AUDIO permission must
 * already be granted; without it, or without a recognition model, this throws [AudioException].
 */
suspend fun transcribeFromMic(context: Context): String = AudioHub.get(context).transcribeFromMic()

/**
 * "Read aloud": speaks [text] in the voice chosen on the audio screen and returns immediately.
 * Markdown and links are stripped first. Watch [AudioHub.speakState] and [AudioHub.speakError]
 * for the outcome; [stopSpeaking] ends it early.
 */
fun speak(context: Context, text: String) = AudioHub.get(context).speak(text)

fun stopSpeaking(context: Context) = AudioHub.get(context).stopSpeaking()

/** A message for the user explaining why an audio action failed. */
fun audioErrorMessage(context: Context, error: Throwable): String = when (error) {
    is AudioException -> context.getString(
        when (error.reason) {
            AudioException.Reason.NO_MIC_PERMISSION -> R.string.audio_err_mic_permission
            AudioException.Reason.MIC_FAILED -> R.string.audio_err_mic_failed
            AudioException.Reason.NOTHING_RECORDED -> R.string.audio_err_nothing_recorded
            AudioException.Reason.NO_SPEECH_MODEL -> R.string.audio_err_no_speech_model
            AudioException.Reason.NO_VOICE -> R.string.audio_err_no_voice
            AudioException.Reason.NO_CLONING_MODEL -> R.string.audio_err_no_cloning_model
            AudioException.Reason.SAMPLE_TOO_SHORT -> R.string.audio_err_sample_short
            AudioException.Reason.UNREADABLE_FILE -> R.string.audio_err_unreadable_file
        },
    )
    else -> error.message ?: error.toString()
}

/** Lets the chat model talk: the text it passes is spoken in the user's selected voice. */
class SpeakTool(private val hub: AudioHub) : Tool {
    override val spec = ToolSpec(
        "speak",
        "Say a text aloud to the user through the device speaker, in the voice the user has chosen. " +
            "Use it when the user asks to hear something spoken or read out. Pass plain words without markup.",
        """{"type":"object","properties":{"text":{"type":"string","description":"What to say"}},"required":["text"]}""",
    )

    override suspend fun run(args: JSONObject, log: AgentLog): String {
        val text = args.optString("text").trim()
        if (text.isEmpty()) return "Error: empty text"
        return try {
            val pcm = hub.speakAndReturn(text)
            log.add(LogKind.NOTE, "speak", text)
            "Speaking aloud now: ${pcm.durationMs / 1000} seconds of audio."
        } catch (e: AudioException) {
            "Error: no voice is available. The user has to install a speech model on the audio screen."
        } catch (e: SpeechException) {
            "Error: ${e.message}"
        }
    }
}
