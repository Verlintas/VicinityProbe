/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.probe

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.L
import com.vicinityprobe.model.domain.ProbeCatalog
import com.vicinityprobe.model.domain.ProbeSpec

enum class CapabilityStatus { SUPPORTED, NO_HARDWARE, PERMISSION_MISSING, FEATURE_OFF }

data class Capability(
    val probeId: String,
    val name: L,
    val spec: ProbeSpec,
    val status: CapabilityStatus,
    val requiredPermissions: List<String> = emptyList(),
)

object Perms {
    const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
    const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    const val NEARBY_WIFI = Manifest.permission.NEARBY_WIFI_DEVICES
    const val BT_SCAN = Manifest.permission.BLUETOOTH_SCAN
    const val BT_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
    const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
    const val ACTIVITY = Manifest.permission.ACTIVITY_RECOGNITION
    const val BODY = Manifest.permission.BODY_SENSORS

    val runtime = listOf(FINE, COARSE, NEARBY_WIFI, BT_SCAN, BT_CONNECT, RECORD_AUDIO, ACTIVITY, BODY)
}

/** 能力预检:运行时枚举设备对探测目录的支持情况 */
object CapabilityProbe {
    fun enumerate(ctx: Context): List<Capability> {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val allSensors = sm.getSensorList(Sensor.TYPE_ALL)
        val sensorStrings = allSensors.mapNotNull { it.stringType }.toSet()
        val sensorTypes = allSensors.map { it.type }.toSet()

        val sensorStringMap = mapOf(
            "sensor.activity" to "android.sensor.activity",
            "sensor.device_orientation" to "android.sensor.device_orientation",
            "sensor.pick_up" to "android.sensor.pick_up_gesture",
            "sensor.wrist_tilt" to "android.sensor.wrist_tilt_gesture",
            "sensor.wake" to "android.sensor.wake_gesture",
            "sensor.glance" to "android.sensor.glance_gesture",
            "sensor.tilt" to "android.sensor.tilt_detector",
            "sensor.shake" to "android.sensor.shake",
            "sensor.flip" to "android.sensor.flip",
            "sensor.free_fall" to "android.sensor.free_fall",
        )
        val sensorIntMap = mapOf(
            "sensor.accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "sensor.accelerometer_uncal" to Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            "sensor.gyroscope" to Sensor.TYPE_GYROSCOPE,
            "sensor.gyroscope_uncal" to Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            "sensor.magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "sensor.magnetometer_uncal" to Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            "sensor.gravity" to Sensor.TYPE_GRAVITY,
            "sensor.linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "sensor.rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
            "sensor.game_rotation_vector" to Sensor.TYPE_GAME_ROTATION_VECTOR,
            "sensor.geomagnetic_rotation" to Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            "sensor.orientation" to Sensor.TYPE_ORIENTATION,
            "sensor.light" to Sensor.TYPE_LIGHT,
            "sensor.proximity" to Sensor.TYPE_PROXIMITY,
            "sensor.pressure" to Sensor.TYPE_PRESSURE,
            "sensor.humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "sensor.temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
            "sensor.step_counter" to Sensor.TYPE_STEP_COUNTER,
            "sensor.step_detector" to Sensor.TYPE_STEP_DETECTOR,
            "sensor.significant_motion" to Sensor.TYPE_SIGNIFICANT_MOTION,
            "sensor.heart_rate" to Sensor.TYPE_HEART_RATE,
            "sensor.heart_beat" to Sensor.TYPE_HEART_BEAT,
            "sensor.offbody" to Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,
        )

        val out = ArrayList<Capability>()

        fun sensorCap(spec: ProbeSpec): Capability {
            val hasHardware = if (sensorStringMap.containsKey(spec.id)) {
                sensorStrings.contains(sensorStringMap[spec.id])
            } else {
                sensorTypes.contains(sensorIntMap[spec.id])
            }
            val perm = spec.requiredPermissions.firstOrNull {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
            val status = when {
                !hasHardware -> CapabilityStatus.NO_HARDWARE
                perm != null -> CapabilityStatus.PERMISSION_MISSING
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(spec.id, nameL(spec.name), spec, status, listOfNotNull(perm))
        }

        ProbeCatalog.all.filter { it.id.startsWith("sensor.") }.forEach { out.add(sensorCap(it)) }

        val fineOk = ContextCompat.checkSelfPermission(ctx, Perms.FINE) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(ctx, Perms.COARSE) == PackageManager.PERMISSION_GRANTED
        val locOk = fineOk || coarseOk
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { false }

        fun locationCap(spec: ProbeSpec, needsGps: Boolean): Capability {
            val status = when {
                !locOk -> CapabilityStatus.PERMISSION_MISSING
                needsGps && !gpsEnabled -> CapabilityStatus.FEATURE_OFF
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(spec.id, nameL(spec.name), spec, status, listOf(Perms.FINE))
        }
        out.add(locationCap(ProbeCatalog.byId("location")!!, needsGps = false))
        out.add(locationCap(ProbeCatalog.byId("gnss")!!, needsGps = true))
        out.add(locationCap(ProbeCatalog.byId("nmea")!!, needsGps = true))
        out.add(locationCap(ProbeCatalog.byId("gnss_raw")!!, needsGps = false))
        out.add(locationCap(ProbeCatalog.byId("gnss_hw")!!, needsGps = false))

        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val nearbyOk = ContextCompat.checkSelfPermission(ctx, Perms.NEARBY_WIFI) == PackageManager.PERMISSION_GRANTED
        val wifiEnabled = try { wifi.isWifiEnabled } catch (_: Throwable) { false }
        fun wifiCap(spec: ProbeSpec, needsLocation: Boolean): Capability {
            val status = when {
                !nearbyOk -> CapabilityStatus.PERMISSION_MISSING
                needsLocation && !locOk -> CapabilityStatus.PERMISSION_MISSING
                !wifiEnabled -> CapabilityStatus.FEATURE_OFF
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(spec.id, nameL(spec.name), spec, status, listOf(Perms.NEARBY_WIFI))
        }
        out.add(wifiCap(ProbeCatalog.byId("wifi")!!, needsLocation = false))
        out.add(wifiCap(ProbeCatalog.byId("wifi_dynamic")!!, needsLocation = false))
        out.add(wifiCap(ProbeCatalog.byId("wifi_scan")!!, needsLocation = true))
        out.add(wifiCap(ProbeCatalog.byId("wifi_direct")!!, needsLocation = false))
        out.add(wifiCap(ProbeCatalog.byId("wifi_aware")!!, needsLocation = false))
        out.add(Capability("wifi_rtt", nameL("WiFi RTT 测距|WiFi RTT ranging"), ProbeCatalog.byId("wifi_rtt")!!,
            if (locOk) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_MISSING, listOf(Perms.FINE)))
        out.add(Capability("cellular", nameL("蜂窝网络|Cellular network"), ProbeCatalog.byId("cellular")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("cellular_series", nameL("蜂窝信号时序|Cellular signal series"), ProbeCatalog.byId("cellular_series")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("connectivity", nameL("网络接口|Connectivity"), ProbeCatalog.byId("connectivity")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("network_stats", nameL("流量与套接字|Traffic & sockets"), ProbeCatalog.byId("network_stats")!!, CapabilityStatus.SUPPORTED))

        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val adapter = bm?.adapter
        val btScanOk = ContextCompat.checkSelfPermission(ctx, Perms.BT_SCAN) == PackageManager.PERMISSION_GRANTED
        val btConnectOk = ContextCompat.checkSelfPermission(ctx, Perms.BT_CONNECT) == PackageManager.PERMISSION_GRANTED
        fun btCap(spec: ProbeSpec, needsConnect: Boolean): Capability {
            val permOk = if (needsConnect) btConnectOk else btScanOk
            val status = when {
                adapter == null -> CapabilityStatus.NO_HARDWARE
                !permOk -> CapabilityStatus.PERMISSION_MISSING
                !adapter.isEnabled -> CapabilityStatus.FEATURE_OFF
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(spec.id, nameL(spec.name), spec, status, listOf(if (needsConnect) Perms.BT_CONNECT else Perms.BT_SCAN))
        }
        out.add(btCap(ProbeCatalog.byId("bluetooth")!!, needsConnect = false))
        out.add(btCap(ProbeCatalog.byId("bt_classic")!!, needsConnect = true))
        out.add(btCap(ProbeCatalog.byId("bt_paired")!!, needsConnect = true))

        val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(ctx)
        out.add(Capability("nfc", nameL("NFC 能力与标签|NFC capability & tags"), ProbeCatalog.byId("nfc")!!,
            if (nfcAdapter == null) CapabilityStatus.NO_HARDWARE else CapabilityStatus.SUPPORTED))
        val irManager = ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? android.hardware.ConsumerIrManager
        out.add(Capability("infrared", nameL("红外发射器|IR emitter"), ProbeCatalog.byId("infrared")!!,
            if (irManager != null && irManager.hasIrEmitter()) CapabilityStatus.SUPPORTED else CapabilityStatus.NO_HARDWARE))
        val fm = try {
            val rmClass = Class.forName("android.hardware.radio.RadioManager")
            val svc = ctx.getSystemService("radio")
            if (svc == null) false
            else (rmClass.getMethod("getModuleList").invoke(svc) as? List<*>)?.isNotEmpty() == true
        } catch (_: Throwable) { false }
        out.add(Capability("fm_radio", nameL("FM 调谐器|FM radio tuner"), ProbeCatalog.byId("fm_radio")!!,
            if (fm) CapabilityStatus.SUPPORTED else CapabilityStatus.NO_HARDWARE))

        val audioOk = ContextCompat.checkSelfPermission(ctx, Perms.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out.add(Capability("noise", nameL("环境声压级|Ambient SPL"), ProbeCatalog.byId("noise")!!,
            if (audioOk) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_MISSING, listOf(Perms.RECORD_AUDIO)))
        out.add(Capability("audio_state", nameL("音频设备状态|Audio state"), ProbeCatalog.byId("audio_state")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("battery", nameL("电池电气参数|Battery"), ProbeCatalog.byId("battery")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("device", nameL("设备静态信息|Device info"), ProbeCatalog.byId("device")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("system", nameL("系统资源状态|System resources"), ProbeCatalog.byId("system")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("thermal", nameL("热状态|Thermal status"), ProbeCatalog.byId("thermal")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("power_state", nameL("CPU 电源状态|CPU power state"), ProbeCatalog.byId("power_state")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("kernel", nameL("内核与安全|Kernel & security"), ProbeCatalog.byId("kernel")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("display", nameL("显示能力|Display capabilities"), ProbeCatalog.byId("display")!!, CapabilityStatus.SUPPORTED))
        out.add(Capability("storage", nameL("存储卷|Storage volumes"), ProbeCatalog.byId("storage")!!, CapabilityStatus.SUPPORTED))

        val connected = try { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetwork != null } catch (_: Throwable) { false }
        fun netCap(spec: ProbeSpec): Capability = Capability(spec.id, nameL(spec.name), spec,
            if (connected) CapabilityStatus.SUPPORTED else CapabilityStatus.FEATURE_OFF)
        out.add(netCap(ProbeCatalog.byId("net_arp")!!))
        out.add(netCap(ProbeCatalog.byId("net_portscan")!!))
        out.add(netCap(ProbeCatalog.byId("net_http_fingerprint")!!))
        out.add(netCap(ProbeCatalog.byId("net_dns")!!))
        out.add(netCap(ProbeCatalog.byId("net_ssdp")!!))
        out.add(netCap(ProbeCatalog.byId("net_ping")!!))

        return out
    }

    fun supportedCount(caps: List<Capability>): Int = caps.count { it.status == CapabilityStatus.SUPPORTED }

    fun nameL(bil: String): L {
        val parts = bil.split("|")
        return L(parts.getOrElse(0) { bil }, parts.getOrElse(1) { bil })
    }
}
