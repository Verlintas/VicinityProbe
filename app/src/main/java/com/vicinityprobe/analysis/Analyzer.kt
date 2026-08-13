package com.vicinityprobe.analysis

import com.vicinityprobe.model.EnvironmentAnalysis
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeReport
import com.vicinityprobe.model.RadarAxis
import com.vicinityprobe.model.WeatherComparison
import com.vicinityprobe.model.bil
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

object Analyzer {
    const val SCENE_INDOOR = "indoor"
    const val SCENE_OUTDOOR = "outdoor"
    const val SCENE_MOTION = "motion"
    const val SCENE_VEHICLE = "vehicle"
    const val SCENE_UNKNOWN = "unknown"

    fun sceneLabel(scene: String): String = when (scene) {
        SCENE_INDOOR -> bil(Labels.SCENE_INDOOR.zh, Labels.SCENE_INDOOR.en)
        SCENE_OUTDOOR -> bil(Labels.SCENE_OUTDOOR.zh, Labels.SCENE_OUTDOOR.en)
        SCENE_MOTION -> bil(Labels.SCENE_MOTION.zh, Labels.SCENE_MOTION.en)
        SCENE_VEHICLE -> bil(Labels.SCENE_VEHICLE.zh, Labels.SCENE_VEHICLE.en)
        else -> bil(Labels.SCENE_UNKNOWN.zh, Labels.SCENE_UNKNOWN.en)
    }

    fun analyze(report: ProbeReport, weather: WeatherComparison? = null): EnvironmentAnalysis {
        val num = { id: String, key: String ->
            report.results.firstOrNull { it.id == id }
                ?.metrics?.firstOrNull { it.key == key }
                ?.value?.let { numOf(it) }
        }
        val metricStr = { id: String, key: String ->
            report.results.firstOrNull { it.id == id }?.metrics?.firstOrNull { it.key == key }?.value
        }

        val light = num("sensor.light", "avg")
        val noise = num("noise", "avg")
        val temp = num("sensor.temperature", "avg")
        val hum = num("sensor.humidity", "avg")
        val rsrp = num("cellular", "signal")
        val wifiRssi = num("wifi", "rssi")
        val accuracy = num("location", "accuracy")
        val speed = num("location", "speed")
        val battery = num("battery", "level")

        val scores = LinkedHashMap<String, Double>()
        light?.let { scores["lighting"] = lightScore(it) }
        noise?.let { scores["noise"] = noiseScore(it) }
        if (temp != null || hum != null) scores["climate"] = climateScore(temp, hum)
        (rsrp ?: wifiRssi)?.let { scores["signal"] = signalScore(rsrp, wifiRssi) }
        accuracy?.let { scores["gps"] = gpsScore(it) }

        val overall = if (scores.isEmpty()) 0.0 else scores.values.average()

        val scene = inferScene(speed, metricStr("sensor.activity", "activity"), light)

        val suggestions = buildSuggestions(
            light = light, noise = noise, temp = temp, hum = hum,
            rsrp = rsrp, wifiRssi = wifiRssi, accuracy = accuracy,
            battery = battery, openNetworks = metricStr("wifi_scan", "open"),
        )

        val radar = scores.entries.map { (key, v) ->
            val label = when (key) {
                "lighting" -> bil(Labels.SCORE_LIGHT.zh, Labels.SCORE_LIGHT.en)
                "noise" -> bil(Labels.SCORE_NOISE.zh, Labels.SCORE_NOISE.en)
                "climate" -> bil(Labels.SCORE_CLIMATE.zh, Labels.SCORE_CLIMATE.en)
                "signal" -> bil(Labels.SCORE_SIGNAL.zh, Labels.SCORE_SIGNAL.en)
                else -> bil(Labels.SCORE_GPS.zh, Labels.SCORE_GPS.en)
            }
            RadarAxis(label, (v * 10.0).roundToLong() / 10.0)
        }

        return EnvironmentAnalysis(
            scores = scores,
            overallScore = (overall * 10.0).roundToLong() / 10.0,
            scene = sceneLabel(scene),
            weather = weather,
            suggestions = suggestions,
            radar = radar,
        )
    }

    private fun numOf(v: String): Double? {
        val match = Regex("-?\\d+\\.?\\d*").find(v) ?: return null
        return match.value.toDoubleOrNull()
    }

    private fun lightScore(lux: Double): Double = when {
        lux < 20 -> 15.0
        lux < 50 -> 30.0
        lux < 100 -> 50.0
        lux < 300 -> 75.0
        lux <= 1500 -> 95.0
        else -> 70.0
    }

    private fun noiseScore(db: Double): Double = when {
        db < 35 -> 95.0
        db < 45 -> 85.0
        db < 60 -> 70.0
        db < 75 -> 45.0
        else -> 20.0
    }

    private fun climateScore(tempC: Double?, humPct: Double?): Double {
        var score = 95.0
        if (tempC != null) {
            val d = (tempC - 22.0).absoluteValue
            score -= when {
                d <= 4 -> 0.0
                d <= 10 -> 20.0
                d <= 16 -> 40.0
                else -> 60.0
            }
        }
        if (humPct != null) {
            val d = (humPct - 50.0).absoluteValue
            score -= when {
                d <= 10 -> 0.0
                d <= 25 -> 15.0
                d <= 45 -> 30.0
                else -> 45.0
            }
        }
        return score.coerceIn(0.0, 95.0)
    }

    private fun signalScore(rsrp: Double?, wifiRssi: Double?): Double {
        val v = rsrp ?: wifiRssi
        return when {
            v == null -> 50.0
            rsrp != null && v >= -90 -> 95.0
            rsrp != null && v >= -105 -> 80.0
            rsrp != null && v >= -115 -> 60.0
            rsrp != null -> 35.0
            v >= -55 -> 90.0
            v >= -70 -> 75.0
            v >= -85 -> 55.0
            else -> 30.0
        }
    }

    private fun gpsScore(accuracyM: Double): Double = when {
        accuracyM <= 10 -> 95.0
        accuracyM <= 30 -> 80.0
        accuracyM <= 100 -> 60.0
        else -> 35.0
    }

    private fun inferScene(speed: Double?, activity: String?, light: Double?): String {
        if (speed != null && speed > 15) return SCENE_VEHICLE
        if (speed != null && speed > 1.5) return SCENE_MOTION
        if (activity != null && (activity.contains("vehicle") || activity.contains("乘车"))) return SCENE_VEHICLE
        if (activity != null && (activity.contains("walking") || activity.contains("running") || activity.contains("bicycle"))) return SCENE_MOTION
        if (light != null) return if (light < 100) SCENE_INDOOR else SCENE_OUTDOOR
        return SCENE_UNKNOWN
    }

    private fun buildSuggestions(
        light: Double?, noise: Double?, temp: Double?, hum: Double?,
        rsrp: Double?, wifiRssi: Double?, accuracy: Double?, battery: Double?,
        openNetworks: String?,
    ): List<String> {
        val out = ArrayList<String>()
        if (light != null && light < 150) out.add(bil("光照偏暗(≈${fmt(light)}lux),建议增加照明或移向明亮处", "Low light (≈${fmt(light)} lux); consider adding light"))
        if (light != null && light > 1500) out.add(bil("光照过强(≈${fmt(light)}lux),可能产生眩光", "Very bright (≈${fmt(light)} lux); possible glare"))
        if (noise != null && noise > 70) out.add(bil("环境噪音偏高(≈${fmt(noise)}dB),建议远离声源", "High noise (≈${fmt(noise)} dB); consider moving away from source"))
        if (temp != null && (temp < 16 || temp > 30)) out.add(bil("温度≈${fmt(temp)}°C,偏离舒适区间(16-30°C)", "Temperature ≈${fmt(temp)}°C, outside comfortable range (16-30°C)"))
        if (hum != null && hum < 35) out.add(bil("空气干燥(≈${fmt(hum)}%),建议保湿", "Dry air (≈${fmt(hum)}%); consider humidifying"))
        if (hum != null && hum > 65) out.add(bil("空气潮湿(≈${fmt(hum)}%),注意通风防潮", "Humid (≈${fmt(hum)}%); ventilate to avoid dampness"))
        if (rsrp != null && rsrp < -115) out.add(bil("蜂窝信号较弱(RSRP ${fmt(rsrp)}dBm),通话或上网可能不稳", "Weak cellular signal (RSRP ${fmt(rsrp)} dBm)"))
        if (wifiRssi != null && rsrp == null && wifiRssi < -75) out.add(bil("WiFi 信号较弱(RSSI ${fmt(wifiRssi)}dBm)", "Weak WiFi signal (RSSI ${fmt(wifiRssi)} dBm)"))
        if (accuracy != null && accuracy > 100) out.add(bil("GPS 定位精度较差(≈${fmt(accuracy)}m),建议到开阔处", "Poor GPS accuracy (≈${fmt(accuracy)} m); try open area"))
        if (battery != null && battery < 20) out.add(bil("电量偏低(${fmt(battery)}%),建议尽快充电", "Battery low (${fmt(battery)}%); charge soon"))
        if (openNetworks != null && numOf(openNetworks) ?: 0.0 > 0) out.add(bil("附近存在开放 WiFi,请勿连接未知开放网络传输敏感信息", "Open WiFi networks detected; avoid sending sensitive data over them"))
        return out
    }

    private fun fmt(v: Double): String = if (v.absoluteValue >= 100) "%.0f".format(v) else "%.1f".format(v)
}
