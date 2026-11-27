package com.voiceagent.stt

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class SttEvent {
    data object Connected : SttEvent()
    data class Partial(val text: String) : SttEvent()
    data class Final(val text: String) : SttEvent()
    data object SpeechStart : SttEvent()
    data object SpeechEnd : SttEvent()
    data class EndOfTurn(val text: String, val confidence: Double) : SttEvent()
    data class Error(val message: String) : SttEvent()
}

enum class DeepgramMode {
    NOVA3_MULTI,
    FLUX_EN
}

class DeepgramRealtimeWsClient {
    companion object {
        private const val TAG = "DeepgramRealtimeWS"
        private const val FLUX_CHUNK_BYTES = 2560 // 80ms at 16kHz 16-bit mono
        private const val NOVA_MAX_SLICE_BYTES = 8192
        private val json = Json { ignoreUnknownKeys = true }

        fun modeForLanguage(languageCode: String): DeepgramMode {
            return if (languageCode.equals("en-IN", ignoreCase = true) ||
                languageCode.equals("en", ignoreCase = true)
            ) {
                DeepgramMode.FLUX_EN
            } else {
                DeepgramMode.NOVA3_MULTI
            }
        }

        fun buildWebSocketUrl(languageCode: String): String {
            return when (modeForLanguage(languageCode)) {
                DeepgramMode.NOVA3_MULTI ->
                    "${PipelineConfig.DEEPGRAM_V1_WS_BASE}" +
                        "?model=${PipelineConfig.DEEPGRAM_NOVA_MODEL}" +
                        "&language=multi" +
                        "&language_hint=en" +
                        "&language_hint=hi" +
                        "&keyterm=Sarah" +
                        "&smart_format=true" +
                        "&interim_results=true" +
                        "&vad_events=true" +
                        "&encoding=linear16" +
                        "&sample_rate=${PipelineConfig.SAMPLE_RATE_HZ}" +
                        "&channels=1"

                DeepgramMode.FLUX_EN ->
                    "${PipelineConfig.DEEPGRAM_V2_WS_BASE}" +
                        "?model=${PipelineConfig.DEEPGRAM_FLUX_MODEL}" +
                        "&keyterm=Sarah" +
                        "&encoding=linear16" +
                        "&sample_rate=${PipelineConfig.SAMPLE_RATE_HZ}"
            }
        }

        fun parseMessage(raw: String, mode: DeepgramMode): List<SttEvent> {
            return try {
                val obj = json.parseToJsonElement(raw).jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (type) {
                    "Results" -> {
                        val txt = obj["channel"]?.jsonObject
                            ?.get("alternatives")?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("transcript")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull == true
                        val speechFinal = obj["speech_final"]?.jsonPrimitive?.booleanOrNull == true
                        val out = mutableListOf<SttEvent>()
                        if (txt.isNotBlank()) {
                            out += if (isFinal) SttEvent.Final(txt) else SttEvent.Partial(txt)
                            if (speechFinal) out += SttEvent.SpeechEnd
                        }
                        out
                    }

                    "SpeechStarted" -> listOf(SttEvent.SpeechStart)
                    "UtteranceEnd" -> listOf(SttEvent.SpeechEnd)

                    "TurnInfo" -> {
                        val evName = obj["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val txt = obj["transcript"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val conf = obj["end_of_turn_confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val out = mutableListOf<SttEvent>()
                        when (evName) {
                            "StartOfTurn" -> {
                                out += SttEvent.SpeechStart
                                if (txt.isNotBlank()) out += SttEvent.Partial(txt)
                            }
                            "Update", "TurnResumed" -> {
                                if (txt.isNotBlank()) out += SttEvent.Partial(txt)
                            }
                            "EndOfTurn" -> {
                                if (txt.isNotBlank()) {
                                    out += SttEvent.Final(txt)
                                    out += SttEvent.EndOfTurn(txt, conf)
                                } else {
                                    out += SttEvent.SpeechEnd
                                }
                            }
                        }
                        out
                    }

                    "Error", "error" -> {
                        val msg = obj["message"]?.jsonPrimitive?.contentOrNull
                            ?: obj["description"]?.jsonPrimitive?.contentOrNull
                            ?: obj["err_msg"]?.jsonPrimitive?.contentOrNull
                            ?: raw
                        listOf(SttEvent.Error(msg))
                    }

                    else -> emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing Deepgram ($mode) frame: ${raw.take(120)}", e)
                emptyList()
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SttEvent> = _events.asSharedFlow()

    @Volatile
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private var openDeferred: CompletableDeferred<Boolean>? = null
    private var keepAliveJob: Job? = null

    @Volatile
    private var activeLanguageCode: String = PipelineConfig.DEFAULT_LANGUAGE_CODE

    @Volatile
    private var activeMode: DeepgramMode = modeForLanguage(PipelineConfig.DEFAULT_LANGUAGE_CODE)

    @Volatile
    private var lastAudioSentAtMs: Long = 0L

    private val audioBufLock = Any()
    private val fluxRemainderBuf = ByteArrayOutputStream()

    fun isSessionConnected(): Boolean = isConnected.get() && webSocket != null

    fun activeModelTag(): String = when (activeMode) {
        DeepgramMode.NOVA3_MULTI -> "deepgram-nova3-multi"
        DeepgramMode.FLUX_EN -> "deepgram-flux-en"
    }

    suspend fun ensureConnected(languageCode: String = activeLanguageCode): Boolean {
        if (isConnected.get() && webSocket != null && languageCode == activeLanguageCode) {
            return true
        }
        return connect(languageCode)
    }

    suspend fun connect(languageCode: String = PipelineConfig.DEFAULT_LANGUAGE_CODE): Boolean {
        disconnect()
        activeLanguageCode = languageCode
        activeMode = modeForLanguage(languageCode)

        val apiKey = SecretsProvider.deepgramApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Deepgram API key not configured — waiting for user key in AI Services & Keys.")
            return false
        }

        val url = buildWebSocketUrl(languageCode)
        val deferred = CompletableDeferred<Boolean>()
        openDeferred = deferred

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        Log.i(TAG, "Connecting Deepgram WS (mode=$activeMode, lang=$languageCode)...")
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Deepgram WS connected (HTTP ${response.code}, mode=$activeMode)")
                isConnected.set(true)
                lastAudioSentAtMs = System.currentTimeMillis()
                deferred.complete(true)
                startKeepAliveLoopIfNeeded()
                _events.tryEmit(SttEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = parseMessage(text, activeMode)
                for (ev in parsed) {
                    if (ev is SttEvent.Error) {
                        Log.e(TAG, "Deepgram WS error payload: ${ev.message}")
                    }
                    _events.tryEmit(ev)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                Log.e(TAG, "Deepgram WS failure (HTTP $code): ${t.message}")
                isConnected.set(false)
                keepAliveJob?.cancel()
                if (!deferred.isCompleted) deferred.complete(false)
                _events.tryEmit(SttEvent.Error("HTTP $code: ${t.message}"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Deepgram WS closed: $code $reason")
                isConnected.set(false)
                keepAliveJob?.cancel()
                if (!deferred.isCompleted) deferred.complete(false)
            }
        })
        webSocket = ws

        val ok = withTimeoutOrNull(6000L) { deferred.await() } ?: false
        if (!ok) {
            disconnect()
        }
        return ok
    }

    private fun startKeepAliveLoopIfNeeded() {
        keepAliveJob?.cancel()
        if (activeMode != DeepgramMode.NOVA3_MULTI) return
        keepAliveJob = scope.launch {
            while (isActive && isConnected.get()) {
                delay(4000L)
                if (!isConnected.get()) break
                if (System.currentTimeMillis() - lastAudioSentAtMs >= 3500L) {
                    webSocket?.send("""{"type":"KeepAlive"}""")
                }
            }
        }
    }

    fun sendSpeechStart() {
        // Deepgram streams audio immediately on connect without an explicit speech_start JSON frame.
    }

    fun sendAudioPcm16(pcmBytes: ByteArray) {
        if (!isConnected.get() || pcmBytes.isEmpty()) return
        val ws = webSocket ?: return
        lastAudioSentAtMs = System.currentTimeMillis()

        if (activeMode == DeepgramMode.FLUX_EN) {
            synchronized(audioBufLock) {
                fluxRemainderBuf.write(pcmBytes)
                val all = fluxRemainderBuf.toByteArray()
                var offset = 0
                while (all.size - offset >= FLUX_CHUNK_BYTES) {
                    ws.send(all.toByteString(offset, FLUX_CHUNK_BYTES))
                    offset += FLUX_CHUNK_BYTES
                }
                fluxRemainderBuf.reset()
                if (offset < all.size) {
                    fluxRemainderBuf.write(all, offset, all.size - offset)
                }
            }
            return
        }

        var offset = 0
        while (offset < pcmBytes.size) {
            val len = minOf(NOVA_MAX_SLICE_BYTES, pcmBytes.size - offset)
            ws.send(pcmBytes.toByteString(offset, len))
            offset += len
        }
    }

    fun sendAudioShorts(samples: ShortArray, count: Int = samples.size) {
        if (!isConnected.get() || count <= 0) return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        sendAudioPcm16(bytes)
    }

    private fun flushResidualFluxAudio() {
        if (activeMode != DeepgramMode.FLUX_EN || !isConnected.get()) return
        val ws = webSocket ?: return
        synchronized(audioBufLock) {
            val rem = fluxRemainderBuf.toByteArray()
            if (rem.isNotEmpty()) {
                ws.send(rem.toByteString(0, rem.size))
                fluxRemainderBuf.reset()
                lastAudioSentAtMs = System.currentTimeMillis()
            }
        }
    }

    fun sendFlushOnly() {
        if (!isConnected.get()) return
        if (activeMode == DeepgramMode.NOVA3_MULTI) {
            webSocket?.send("""{"type":"Finalize"}""")
        }
    }

    fun sendFlushAndContinue() {
        sendFlushOnly()
    }

    fun sendSpeechEndAndFlush() {
        if (!isConnected.get()) return
        flushResidualFluxAudio()
        when (activeMode) {
            DeepgramMode.NOVA3_MULTI -> webSocket?.send("""{"type":"Finalize"}""")
            DeepgramMode.FLUX_EN -> webSocket?.send("""{"type":"ForceEndTurn"}""")
        }
    }

    fun disconnect() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        val wasConnected = isConnected.getAndSet(false)
        val ws = webSocket
        webSocket = null
        synchronized(audioBufLock) {
            fluxRemainderBuf.reset()
        }
        try {
            if (wasConnected && ws != null) {
                if (activeMode == DeepgramMode.FLUX_EN) {
                    ws.send("""{"type":"ForceEndTurn"}""")
                }
                ws.send("""{"type":"CloseStream"}""")
            }
            ws?.close(1000, "Reset")
        } catch (_: Exception) {}
    }
}
