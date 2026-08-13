package com.vicinityprobe.analysis

import com.vicinityprobe.model.WeatherComparison
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class WmCurrent(
    val temperature_2m: Double,
    val relative_humidity_2m: Double,
    val surface_pressure: Double,
    val weather_code: Int,
    val wind_speed_10m: Double,
)

@Serializable
private data class WmResponse(val current: WmCurrent)

object WeatherCodes {
    fun text(code: Int): String = when (code) {
        0 -> "晴|Clear sky"
        1 -> "大部晴朗|Mainly clear"
        2 -> "多云|Partly cloudy"
        3 -> "阴天|Overcast"
        45, 48 -> "雾|Fog"
        51, 53, 55 -> "毛毛雨|Drizzle"
        56, 57 -> "冻雨|Freezing drizzle"
        61, 63, 65 -> "雨|Rain"
        66, 67 -> "冻雨|Freezing rain"
        71, 73, 75 -> "雪|Snowfall"
        77 -> "雪粒|Snow grains"
        80, 81, 82 -> "阵雨|Rain showers"
        85, 86 -> "阵雪|Snow showers"
        95 -> "雷暴|Thunderstorm"
        96, 99 -> "雷暴伴冰雹|Thunderstorm with hail"
        else -> "未知($code)|Unknown($code)"
    }
}

object WeatherClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(lat: Double, lon: Double): WeatherComparison = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,surface_pressure,weather_code,wind_speed_10m&timezone=auto"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "VicinityProbe/1.0")
            val code = conn.responseCode
            if (code != 200) {
                return@withContext WeatherComparison(fetched = false, note = "HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val res = json.decodeFromString<WmResponse>(body)
            WeatherComparison(
                fetched = true,
                temperatureC = res.current.temperature_2m,
                humidityPct = res.current.relative_humidity_2m,
                pressureHpa = res.current.surface_pressure,
                windSpeedKph = res.current.wind_speed_10m,
                conditionCode = res.current.weather_code,
                conditionText = WeatherCodes.text(res.current.weather_code),
            )
        } catch (e: Exception) {
            WeatherComparison(fetched = false, note = e.javaClass.simpleName)
        }
    }
}
