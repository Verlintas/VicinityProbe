package com.vicinityprobe.probe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.vicinityprobe.model.bil
import com.vicinityprobe.model.domain.Measurement
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.QualityLevels
import com.vicinityprobe.model.domain.QualityReport

/** 电池电气参数采样器 */
class BatterySampler : Sampler {
    override val spec: ProbeSpec = ProbeCatalog.byId("battery")!!

    override suspend fun run(ctx: Context, session: SessionContext): Measurement {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val attrs = LinkedHashMap<String, String>()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) attrs["level_pct"] = (level * 100 / scale).toString()
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        attrs["charging_state"] = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中|Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中|Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满|Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电|Not charging"
            else -> "UNKNOWN($status)"
        }
        attrs["plugged_type"] = when {
            plugged == BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            plugged == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线|Wireless"
            plugged == 0 -> "未连接|Unplugged"
            else -> "UNKNOWN($plugged)"
        }
        attrs["health"] = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "良好|Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热|Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "损坏|Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压|Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "过冷|Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障|Failure"
            else -> "UNKNOWN($health)"
        }
        val tempC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0)
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.div(1000.0)
        if (tempC != null && tempC > 0) attrs["temperature_c"] = String.format("%.1f", tempC)
        if (voltage != null && voltage > 0) attrs["voltage_v"] = String.format("%.3f", voltage)
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Int.MIN_VALUE) attrs["current_ma"] = String.format("%.0f", current / 1000.0)
        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (chargeCounter != Int.MIN_VALUE) attrs["charge_counter_mah"] = String.format("%.0f", chargeCounter / 1000.0)
        powerProfileCapacity(ctx)?.let { attrs["rated_capacity_mah"] = it.toString() }

        val stats = LinkedHashMap<String, com.vicinityprobe.model.domain.ChannelStats>()
        tempC?.let { stats["temperature"] = com.vicinityprobe.model.domain.ChannelStats.compute(floatArrayOf(it.toFloat()), "°C") }
        voltage?.let { stats["voltage"] = com.vicinityprobe.model.domain.ChannelStats.compute(floatArrayOf(it.toFloat()), "V") }
        current.takeIf { it != Int.MIN_VALUE }?.let { stats["current"] = com.vicinityprobe.model.domain.ChannelStats.compute(floatArrayOf(it / 1000f), "mA") }

        return Measurement(
            spec = spec, status = QualityLevels.CODE_OK,
            attributes = attrs, stats = stats,
            quality = QualityReport(QualityLevel.GOOD, QualityLevels.CODE_OK, "", sampleCount = 1, achievedRateHz = 0.0),
        )
    }

    private fun powerProfileCapacity(ctx: Context): Int? {
        return try {
            val clazz = Class.forName("com.android.internal.os.PowerProfile")
            val ctor = clazz.getConstructor(Context::class.java)
            val profile = ctor.newInstance(ctx)
            val method = clazz.getMethod("getAveragePower", String::class.java)
            val v = method.invoke(profile, "battery.capacity") as? Double
            v?.toInt()
        } catch (_: Throwable) { null }
    }
}
