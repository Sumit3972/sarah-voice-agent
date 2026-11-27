package com.voiceagent.tts

import android.util.Log
import com.voiceagent.audio.LowLatencyAudioPlayer
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.msgpack.core.MessagePack
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class FishVoiceMetadata(
    val id: String,
    val title: String,
    val rawJson: String
)

class FishAudioLiveWsClient(
    private val audioPlayer: LowLatencyAudioPlayer
) {
    companion object {
        private const val TAG = "FishAudioWSTts"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val keyIndex = AtomicInteger(0)

    @Volatile
    private var webSocket: WebSocket? = null
    private val sessionActive = AtomicBoolean(false)
    private var startTimeMs = 0L
    private var firstAudioLogged = false
    private var completionDeferred: CompletableDeferred<Unit>? = null

    suspend fun verifyVoiceModel(voiceId: String = SecretsProvider.fishAudioVoiceId()): FishVoiceMetadata? =
        withContext(Dispatchers.IO) {
            val apiKey = SecretsProvider.fishAudioKeys().firstOrNull().orEmpty()
            if (apiKey.isBlank()) return@withContext null
            val req = Request.Builder()
                .url("${PipelineConfig.FISH_MODEL_INFO_URL}/$voiceId")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "GET /model/$voiceId HTTP ${resp.code}: ${body.take(200)}")
                        return@use null
                    }
                    val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "Sarah"
                    Log.i(TAG, "Verified Fish Audio voice model: id=$voiceId title=$title")
                    FishVoiceMetadata(voiceId, title, body)
                }
            }.getOrNull()
        }

    suspend fun startSession(latencyMode: String = "balanced"): Boolean {
        abortNow()

        val keyList = SecretsProvider.fishAudioKeys().ifEmpty { return false }
        for (attempt in keyList.indices) {
            val apiKey = keyList[(keyIndex.get() + attempt) % keyList.size]
            val openDeferred = CompletableDeferred<Boolean>()
            val finishSignal = CompletableDeferred<Unit>()
            completionDeferred = finishSignal
            startTimeMs = System.currentTimeMillis()
            firstAudioLogged = false

            val req = Request.Builder()
                .url(PipelineConfig.FISH_WS_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("model", SecretsProvider.fishAudioModel())
                .build()

            audioPlayer.prepareAndPlay(PipelineConfig.FISH_TTS_SAMPLE_RATE)
            sessionActive.set(true)

            webSocket = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val wsMs = System.currentTimeMillis() - startTimeMs
                    Log.i(TAG, "Fish WS opened in ${wsMs}ms (model=${SecretsProvider.fishAudioModel()})")
                    keyIndex.set((keyIndex.get() + attempt) % keyList.size)
                    try {
                        val startBytes = packStartFrame(
                            voiceId = SecretsProvider.fishAudioVoiceId(),
                            sampleRate = PipelineConfig.FISH_TTS_SAMPLE_RATE,
                            latency = latencyMode
                        )
                        webSocket.send(startBytes.toByteString())
                        openDeferred.complete(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed sending Fish start frame", e)
                        openDeferred.complete(false)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingBytes(bytes.toByteArray(), finishSignal)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Fish WS text: $text")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Fish WS failure (HTTP ${response?.code}): ${t.message}")
                    sessionActive.set(false)
                    if (!openDeferred.isCompleted) openDeferred.complete(false)
                    if (!finishSignal.isCompleted) finishSignal.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Fish WS closed: $code $reason")
                    sessionActive.set(false)
                    if (!openDeferred.isCompleted) openDeferred.complete(false)
                    if (!finishSignal.isCompleted) finishSignal.complete(Unit)
                }
            })

            val opened = withTimeoutOrNull(5000L) { openDeferred.await() } ?: false
            if (opened) return true
            abortNow()
        }
        return false
    }

    fun sendTextClause(clause: String) {
        if (!sessionActive.get() || clause.isBlank()) return
        val packed = packTextFrame(clause)
        webSocket?.send(packed.toByteString())
        val flushPacked = packSimpleEvent("flush")
        webSocket?.send(flushPacked.toByteString())
    }

    suspend fun finishAndAwaitPlayback(timeoutMs: Long = 30_000L) {
        if (!sessionActive.get()) return
        try {
            webSocket?.send(packSimpleEvent("stop").toByteString())
        } catch (_: Exception) {}
        withTimeoutOrNull(timeoutMs) {
            completionDeferred?.await()
        }
        audioPlayer.drainAndStop()
        closeWebSocketOnly()
    }

    fun abortNow() {
        sessionActive.set(false)
        try {
            webSocket?.send(packSimpleEvent("stop").toByteString())
        } catch (_: Exception) {}
        closeWebSocketOnly()
        audioPlayer.abortImmediate()
        completionDeferred?.complete(Unit)
    }

    private fun closeWebSocketOnly() {
        try {
            webSocket?.close(1000, "Done")
        } catch (_: Exception) {}
        webSocket = null
    }

    private fun handleIncomingBytes(data: ByteArray, finishSignal: CompletableDeferred<Unit>) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(data)
            if (unpacker.hasNext()) {
                val valObj = unpacker.unpackValue()
                if (valObj.isMapValue) {
                    val map = valObj.asMapValue().map()
                    var eventType = ""
                    var audioBytes: ByteArray? = null
                    for ((k, v) in map) {
                        val key = k.asStringValue().asString()
                        when {
                            key == "event" && v.isStringValue -> eventType = v.asStringValue().asString()
                            key == "audio" && v.isBinaryValue -> audioBytes = v.asBinaryValue().asByteArray()
                        }
                    }
                    if (audioBytes != null && audioBytes.isNotEmpty() && sessionActive.get()) {
                        if (!firstAudioLogged) {
                            firstAudioLogged = true
                            Log.i(TAG, "Fish Audio TTFA=${System.currentTimeMillis() - startTimeMs}ms (first chunk=${audioBytes.size}B)")
                        }
                        audioPlayer.enqueuePcm(audioBytes)
                    }
                    if (eventType == "finish") {
                        Log.i(TAG, "Fish WS finish event received")
                        sessionActive.set(false)
                        if (!finishSignal.isCompleted) finishSignal.complete(Unit)
                    }
                    return
                }
            }
        } catch (_: Exception) {
        }

        if (data.isNotEmpty() && sessionActive.get()) {
            audioPlayer.enqueuePcm(data)
        }
    }

    private fun packStartFrame(voiceId: String, sampleRate: Int, latency: String): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("event")
        packer.packString("start")
        packer.packString("request")
        packer.packMapHeader(8)
        packer.packString("text")
        packer.packString("")
        packer.packString("format")
        packer.packString("pcm")
        packer.packString("sample_rate")
        packer.packInt(sampleRate)
        packer.packString("chunk_length")
        packer.packInt(PipelineConfig.FISH_CHUNK_LENGTH)
        packer.packString("reference_id")
        packer.packString(voiceId)
        packer.packString("latency")
        packer.packString(latency)
        packer.packString("normalize")
        packer.packBoolean(true)
        packer.packString("prosody")
        packer.packMapHeader(2)
        packer.packString("speed")
        packer.packFloat(1.05f)
        packer.packString("volume")
        packer.packInt(0)
        return packer.toByteArray()
    }

    private fun packTextFrame(text: String): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("event")
        packer.packString("text")
        packer.packString("text")
        packer.packString(text)
        return packer.toByteArray()
    }

    private fun packSimpleEvent(eventName: String): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("event")
        packer.packString(eventName)
        return packer.toByteArray()
    }
}
