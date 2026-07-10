package com.voicecontrol.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.io.BufferedReader
import java.io.InputStreamReader

class WeatherClient(private val apiKey: String) {
    companion object {
        private const val BASE_URL = "https://api.openweathermap.org/data/2.5/weather"
    }

    suspend fun getWeather(city: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Weather API key not set. Add in Settings."

        val urlString = "$BASE_URL?q=${Uri.encode(city)}&appid=$apiKey&units=metric&lang=hi"
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream)).readText()
            conn.disconnect()

            if (status in 200..299) parseWeather(response)
            else {
                val err = try { JSONObject(response).optString("message", response) } catch (_: Exception) { response }
                "Weather error $status: $err"
            }
        } catch (e: Exception) {
            "Weather fetch failed: ${e.message}"
        }
    }

    private fun parseWeather(json: String): String {
        val obj = JSONObject(json)
        val name = obj.getString("name")
        val main = obj.getJSONObject("main")
        val temp = main.getDouble("temp").roundToInt()
        val feels = main.getDouble("feels_like").roundToInt()
        val humidity = main.getInt("humidity")
        val weather = obj.getJSONArray("weather").getJSONObject(0)
        val desc = weather.getString("description")
        val wind = obj.getJSONObject("wind").getDouble("speed")

        val lang = if (desc.contains(Regex("[a-zA-Z]"))) "en" else "hi"
        return if (lang == "hi") {
            "$name: $desc, ${temp}°C (feels $feels°), humidity $humidity%, hawa ${wind.toInt()} km/h"
        } else {
            "$name: $desc, ${temp}°C (feels $feels°), humidity $humidity%, wind ${wind.toInt()} km/h"
        }
    }
}

private fun String.roundToInt(): Int = this.toDouble().roundToInt()