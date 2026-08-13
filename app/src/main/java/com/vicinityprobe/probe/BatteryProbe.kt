package com.vicinityprobe.probe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L
import com.vicinityprobe.model.Labels
import com.vicinityprobe.model.ProbeResult
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.bil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BatteryProbe : ProbeUnit {
    override val id = "battery"

    override suspend fun run(ctx: Context, deadlineMs: Long, live: LiveMetrics): List<ProbeResult> {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val metrics = mutableListOf<com.vicinityprobe.model.Metric>()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            metrics.add(metric("level", Labels.LEVEL, "$level%", primary = true))
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        metrics.add(metric("charging", Labels.CHARGING, statusName(status)))
        metrics.add(metric("plug", Labels.PLUG, pluggedName(plugged)))
        metrics.add(metric("health", Labels.HEALTH, healthName(health)))

        val tempC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0)
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.div(1000.0)
        if (tempC != null && tempC > 0) metrics.add(metric("temp", Labels.TEMP_C, fmt(tempC), "°C"))
        if (voltage != null && voltage > 0) metrics.add(metric("voltage", Labels.VOLTAGE, fmt(voltage), "V"))

        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current != Int.MIN_VALUE) {
            metrics.add(metric("current", Labels.CURRENT, "${current / 1000.0} mA", primary = false))
            live.set("battery", Labels.BATTERY.en, "${current / 1000.0} mA")
        }
        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (chargeCounter != Int.MIN_VALUE) {
            metrics.add(metric("charge_counter", Labels.CHARGE_COUNTER, "${chargeCounter / 1000.0} mAh"))
        }
        val capacity = powerProfileCapacity(ctx)
        if (capacity != null) metrics.add(metric("capacity", Labels.CAPACITY, "$capacity mAh"))
        return listOf(resultBuilder("battery", Groups.BATTERY, Labels.BATTERY, ProbeStatus.OK, metrics = metrics))
    }

    private fun statusName(s: Int): String = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> bil("充电中", "Charging")
        BatteryManager.BATTERY_STATUS_DISCHARGING -> bil("放电中", "Discharging")
        BatteryManager.BATTERY_STATUS_FULL -> bil("已充满", "Full")
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> bil("未充电", "Not charging")
        else -> "UNKNOWN($s)"
    }

    private fun pluggedName(p: Int): String = when {
        p == BatteryManager.BATTERY_PLUGGED_AC -> bil("交流电源", "AC")
        p == BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        p == BatteryManager.BATTERY_PLUGGED_WIRELESS -> bil("无线充电", "Wireless")
        p == 0 -> bil("未连接电源", "Unplugged")
        else -> "UNKNOWN($p)"
    }

    private fun healthName(h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> bil("良好", "Good")
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> bil("过热", "Overheat")
        BatteryManager.BATTERY_HEALTH_DEAD -> bil("损坏", "Dead")
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> bil("过压", "Over voltage")
        BatteryManager.BATTERY_HEALTH_COLD -> bil("过冷", "Cold")
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> bil("故障", "Failure")
        else -> "UNKNOWN($h)"
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
