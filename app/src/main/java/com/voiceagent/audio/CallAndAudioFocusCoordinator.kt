package com.voiceagent.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.voiceagent.config.PipelineConfig

/**
 * Coordinates Android call state awareness (pausing ambient mic capture during active cellular/VoIP calls)
 * and transient ducking audio focus during Sarah's TTS replies so music/media ducks smoothly.
 */
class CallAndAudioFocusCoordinator(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            Log.d(PipelineConfig.LOG_TAG, "[AudioFocus] Lost audio focus (code=$change)")
        }
    }

    fun isPhoneOrVoipCallActive(): Boolean {
        val mode = audioManager.mode
        return isCallAudioMode(mode)
    }

    fun requestAssistantDuckFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        activeFocusRequest = req
        val res = runCatching { audioManager.requestAudioFocus(req) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonAssistantFocus() {
        val req = activeFocusRequest ?: return
        activeFocusRequest = null
        runCatching { audioManager.abandonAudioFocusRequest(req) }
    }

    companion object {
        internal fun isCallAudioMode(audioMode: Int): Boolean {
            if (audioMode == AudioManager.MODE_IN_CALL ||
                audioMode == AudioManager.MODE_IN_COMMUNICATION ||
                audioMode == AudioManager.MODE_RINGTONE
            ) {
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                audioMode == AudioManager.MODE_CALL_SCREENING
            ) {
                return true
            }
            return false
        }
    }
}
