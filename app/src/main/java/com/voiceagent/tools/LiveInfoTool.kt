package com.voiceagent.tools

import android.net.Uri
import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.asArrOrNull
import com.voiceagent.config.asObjOrNull
import com.voiceagent.config.asStrOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class LiveInfoTool {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = args["action"].asStrOrNull()?.trim()?.lowercase().orEmpty()
        val query = args["query"].asStrOrNull()?.trim().orEmpty()
        val location = args["location"].asStrOrNull()?.trim().orEmpty()

        when (action) {
            "weather" -> fetchWeather(location.ifBlank { query }.ifBlank { "current location" })
            "web_search" -> fetchWebSearch(query.ifBlank { location })
            "calculate" -> calculate(query)
            "convert" -> convert(query)
            else -> """{"ok":false,"error":"Unknown live_info action='$action'. Use weather|web_search|calculate|convert."}"""
        }
    }

    private fun fetchWeather(location: String): String {
        val target = if (location.equals("current location", true) || location.equals("auto", true)) "" else Uri.encode(location)
        val url = "https://wttr.in/$target?format=j1"
        return runCatching {
            val req = Request.Builder().url(url).addHeader("User-Agent", "curl/8.0").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body.string()).asObjOrNull()
                    ?: throw IllegalStateException("Invalid JSON")
                val cur = obj["current_condition"].asArrOrNull()?.firstOrNull()?.asObjOrNull()
                val nearest = obj["nearest_area"].asArrOrNull()?.firstOrNull()?.asObjOrNull()
                val area = nearest?.get("areaName").asArrOrNull()?.firstOrNull()?.asObjOrNull()?.get("value").asStrOrNull()
                    ?: location
                val tempC = cur?.get("temp_C").asStrOrNull().orEmpty()
                val feelsC = cur?.get("FeelsLikeC").asStrOrNull().orEmpty()
                val humidity = cur?.get("humidity").asStrOrNull().orEmpty()
                val windKmph = cur?.get("windspeedKmph").asStrOrNull().orEmpty()
                val desc = cur?.get("weatherDesc").asArrOrNull()?.firstOrNull()?.asObjOrNull()?.get("value").asStrOrNull().orEmpty()
                val today = obj["weather"].asArrOrNull()?.firstOrNull()?.asObjOrNull()
                val maxC = today?.get("maxtempC").asStrOrNull().orEmpty()
                val minC = today?.get("mintempC").asStrOrNull().orEmpty()

                buildJsonObject {
                    put("ok", true)
                    put("area", area)
                    put("condition", desc)
                    put("temp_c", tempC)
                    put("feels_like_c", feelsC)
                    put("humidity_percent", humidity)
                    put("wind_kmph", windKmph)
                    if (maxC.isNotBlank()) put("today_high_c", maxC)
                    if (minC.isNotBlank()) put("today_low_c", minC)
                }.toString()
            }
        }.getOrElse { err ->
            Log.w(PipelineConfig.LOG_TAG, "[LiveInfo] wttr.in failed: ${err.message}")
            """{"ok":false,"error":"Weather fetch failed: ${err.message}"}"""
        }
    }

    private fun fetchWebSearch(query: String): String {
        if (query.isBlank()) return """{"ok":false,"error":"Search query is required"}"""
        val snippets = mutableListOf<String>()
        var abstract = ""

        runCatching {
            val apiUrl = "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder().url(apiUrl).addHeader("User-Agent", "SarahVoiceAgent/1.0").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = json.parseToJsonElement(resp.body.string()).asObjOrNull()
                    abstract = obj?.get("AbstractText").asStrOrNull().orEmpty().trim()
                    val answer = obj?.get("Answer").asStrOrNull().orEmpty().trim()
                    if (answer.isNotBlank()) snippets += answer
                    obj?.get("RelatedTopics").asArrOrNull()?.take(4)?.forEach { rt ->
                        val t = rt.asObjOrNull()?.get("Text").asStrOrNull()?.trim()
                        if (!t.isNullOrBlank()) snippets += t
                    }
                }
            }
        }

        if (abstract.isBlank() && snippets.isEmpty()) {
            runCatching {
                val htmlUrl = "https://html.duckduckgo.com/html/?q=${Uri.encode(query)}"
                val req = Request.Builder().url(htmlUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
                    .get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = resp.body.string()
                        val matches = Regex("<a class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(html)
                            .take(4)
                        for (m in matches) {
                            val clean = m.groupValues[1]
                                .replace(Regex("<[^>]+>"), " ")
                                .replace("&amp;", "&")
                                .replace("&quot;", "\"")
                                .replace("&#x27;", "'")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            if (clean.isNotBlank()) snippets += clean
                        }
                    }
                }
            }
        }

        return buildJsonObject {
            put("ok", abstract.isNotBlank() || snippets.isNotEmpty())
            put("query", query)
            if (abstract.isNotBlank()) put("summary", abstract.take(600))
            put("snippets", buildJsonArray {
                snippets.distinct().take(4).forEach { s ->
                    putSnippet(s)
                }
            })
        }.toString()
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.putSnippet(value: String) {
        add(kotlinx.serialization.json.JsonPrimitive(value.take(350)))
    }

    private fun calculate(expr: String): String {
        if (expr.isBlank()) return """{"ok":false,"error":"Expression is required"}"""
        return runCatching {
            val res = evalArithmetic(expr)
            val display = if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res).trimEnd('0').trimEnd('.')
            buildJsonObject {
                put("ok", true)
                put("expression", expr)
                put("result", display)
            }.toString()
        }.getOrElse {
            """{"ok":false,"error":"Could not evaluate expression '$expr'"}"""
        }
    }

    private fun convert(query: String): String {
        val m = Regex("([-+]?[0-9]*\\.?[0-9]+)\\s*([a-zA-Z]+)\\s+(?:to|in)\\s+([a-zA-Z]+)", RegexOption.IGNORE_CASE)
            .find(query)
            ?: return """{"ok":false,"error":"Format conversions like '100 usd to inr' or '32 c to f'"}"""
        val amount = m.groupValues[1].toDouble()
        val from = m.groupValues[2].lowercase()
        val to = m.groupValues[3].lowercase()

        val local = when (from to to) {
            "c" to "f", "celsius" to "fahrenheit" -> amount * 9.0 / 5.0 + 32.0
            "f" to "c", "fahrenheit" to "celsius" -> (amount - 32.0) * 5.0 / 9.0
            "km" to "mi", "kilometers" to "miles" -> amount * 0.621371
            "mi" to "km", "miles" to "kilometers" -> amount / 0.621371
            "kg" to "lb", "kg" to "lbs" -> amount * 2.20462
            "lb" to "kg", "lbs" to "kg" -> amount / 2.20462
            "cm" to "in", "cm" to "inches" -> amount / 2.54
            "in" to "cm", "inches" to "cm" -> amount * 2.54
            else -> null
        }
        if (local != null) {
            return buildJsonObject {
                put("ok", true)
                put("result", "%.2f %s".format(local, to))
            }.toString()
        }

        // Currency conversion fallback via Frankfurter public ECB API
        return runCatching {
            val url = "https://api.frankfurter.app/latest?amount=$amount&from=${from.uppercase()}&to=${to.uppercase()}"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body.string()).asObjOrNull()
                val rateVal = obj?.get("rates")?.asObjOrNull()?.get(to.uppercase())?.toString()
                    ?: throw IllegalStateException("Rate missing")
                buildJsonObject {
                    put("ok", true)
                    put("amount", amount)
                    put("from", from.uppercase())
                    put("to", to.uppercase())
                    put("converted_value", rateVal)
                }.toString()
            }
        }.getOrElse {
            """{"ok":false,"error":"Unsupported conversion '$query': ${it.message}"}"""
        }
    }

    private fun evalArithmetic(str: String): Double {
        var pos = -1
        var ch = 0
        fun nextChar() { ch = if (++pos < str.length) str[pos].code else -1 }
        fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) { nextChar(); return true }
            return false
        }
        lateinit var parseExpression: () -> Double
        fun parseFactor(): Double {
            if (eat('+'.code)) return parseFactor()
            if (eat('-'.code)) return -parseFactor()
            val startPos = pos
            var x: Double
            if (eat('('.code)) {
                x = parseExpression()
                eat(')'.code)
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = str.substring(startPos, pos).toDouble()
            } else {
                throw IllegalArgumentException("Unexpected char: ${ch.toChar()}")
            }
            if (eat('^'.code)) x = x.pow(parseFactor())
            return x
        }
        fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                x = when {
                    eat('*'.code) -> x * parseFactor()
                    eat('/'.code) -> x / parseFactor()
                    eat('%'.code) -> x % parseFactor()
                    else -> return x
                }
            }
        }
        parseExpression = {
            var x = parseTerm()
            while (true) {
                x = when {
                    eat('+'.code) -> x + parseTerm()
                    eat('-'.code) -> x - parseTerm()
                    else -> break
                }
            }
            x
        }
        nextChar()
        return parseExpression()
    }
}
