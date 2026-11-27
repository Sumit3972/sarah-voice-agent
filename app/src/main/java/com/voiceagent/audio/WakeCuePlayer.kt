package com.voiceagent.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.voiceagent.R
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.config.WakeCueMode

class WakeCuePlayer(private val context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val chimeSoundId: Int = soundPool.load(context, R.raw.wake_chime, 1)

    @Volatile
    var isChimeLoaded: Boolean = false
        private set

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            isChimeLoaded = (status == 0)
        }
    }

    /**
     * Plays custom chime if WakeCueMode.CUSTOM_CHIME, or stays silent if WakeCueMode.SILENT.
     * Returns suppression duration (ms) so VAD ignores the chime sound itself.
     */
    fun playWakeCueIfEnabled(): Long {
        val mode = SecretsProvider.getWakeCueMode(context)
        if (mode == WakeCueMode.SILENT) {
            Log.d(PipelineConfig.LOG_TAG, "[WakeCue] Silent wake cue")
            return 0L
        }
        if (isChimeLoaded) {
            soundPool.play(chimeSoundId, 0.85f, 0.85f, 1, 0, 1.0f)
            Log.d(PipelineConfig.LOG_TAG, "[WakeCue] Played custom wake_chime.wav")
            return 160L
        }
        return 0L
    }

    fun release() {
        runCatching { soundPool.release() }
    }
}
