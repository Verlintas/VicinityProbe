package com.vicinityprobe.probe

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.vicinityprobe.model.Groups
import com.vicinityprobe.model.L

enum class CapabilityStatus { SUPPORTED, NO_HARDWARE, PERMISSION_MISSING, FEATURE_OFF }

data class Capability(
    val probeId: String,
    val name: L,
    val group: String,
    val status: CapabilityStatus,
    val requiredPermissions: List<String> = emptyList(),
    val description: String = "",
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

    fun label(p: String): String = when (p) {
        FINE, COARSE -> "Location"
        NEARBY_WIFI -> "Nearby WiFi"
        BT_SCAN -> "Bluetooth scan"
        BT_CONNECT -> "Bluetooth connect"
        RECORD_AUDIO -> "Microphone"
        ACTIVITY -> "Activity recognition"
        BODY -> "Body sensors"
        else -> p
    }
}

object CapabilityProbe {
    fun enumerate(ctx: Context): List<Capability> {
        val out = ArrayList<Capability>()
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        fun sensorCap(spec: SensorSpec): Capability {
            val has = SensorSpecs.lookup(sm, spec) != null
            val perm = spec.permission != null && ContextCompat.checkSelfPermission(ctx, spec.permission) != PackageManager.PERMISSION_GRANTED
            val status = when {
                !has -> CapabilityStatus.NO_HARDWARE
                perm -> CapabilityStatus.PERMISSION_MISSING
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(spec.id, spec.name, Groups.SENSOR, status,
                requiredPermissions = listOfNotNull(spec.permission))
        }
        SensorSpecs.all.forEach { out.add(sensorCap(it)) }

        val fineOk = ContextCompat.checkSelfPermission(ctx, Perms.FINE) == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(ctx, Perms.COARSE) == PackageManager.PERMISSION_GRANTED
        val locOk = fineOk || coarseOk
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { false }

        fun locationCap(id: String, name: L, needsGps: Boolean): Capability {
            val status = when {
                !locOk -> CapabilityStatus.PERMISSION_MISSING
                needsGps && !gpsEnabled -> CapabilityStatus.FEATURE_OFF
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(id, name, Groups.LOCATION, status, requiredPermissions = listOf(Perms.FINE))
        }
        out.add(locationCap("location", com.vicinityprobe.model.Labels.LOCATION, needsGps = false))
        out.add(locationCap("gnss", com.vicinityprobe.model.Labels.GNSS, needsGps = true))
        out.add(locationCap("nmea", com.vicinityprobe.model.Labels.NMEA, needsGps = true))

        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val nearbyOk = ContextCompat.checkSelfPermission(ctx, Perms.NEARBY_WIFI) == PackageManager.PERMISSION_GRANTED
        val wifiEnabled = try { wifi.isWifiEnabled } catch (_: Throwable) { false }
        fun wifiCap(id: String, name: L, needsLocation: Boolean): Capability {
            val status = when {
                !nearbyOk -> CapabilityStatus.PERMISSION_MISSING
                needsLocation && !locOk -> CapabilityStatus.PERMISSION_MISSING
                !wifiEnabled -> CapabilityStatus.FEATURE_OFF
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(id, name, Groups.NETWORK, status, requiredPermissions = listOf(Perms.NEARBY_WIFI) + if (needsLocation) listOf(Perms.FINE) else emptyList())
        }
        out.add(wifiCap("wifi", com.vicinityprobe.model.Labels.WIFI, needsLocation = false))
        out.add(wifiCap("wifi_scan", com.vicinityprobe.model.Labels.WIFI_SCAN, needsLocation = true))

        out.add(Capability("cellular", com.vicinityprobe.model.Labels.CELLULAR, Groups.NETWORK, CapabilityStatus.SUPPORTED))
        out.add(Capability("connectivity", com.vicinityprobe.model.Labels.CONNECTIVITY, Groups.NETWORK, CapabilityStatus.SUPPORTED))

        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val adapter = bm?.adapter
        val btScanOk = ContextCompat.checkSelfPermission(ctx, Perms.BT_SCAN) == PackageManager.PERMISSION_GRANTED
        val btConnectOk = ContextCompat.checkSelfPermission(ctx, Perms.BT_CONNECT) == PackageManager.PERMISSION_GRANTED
        fun btCap(id: String, name: L, needsConnect: Boolean): Capability {
            val permOk = if (needsConnect) btConnectOk else btScanOk
            val status = when {
                adapter == null -> CapabilityStatus.NO_HARDWARE
                !permOk -> CapabilityStatus.PERMISSION_MISSING
                !adapter.isEnabled -> CapabilityStatus.FEATURE_OFF
                else -> CapabilityStatus.SUPPORTED
            }
            return Capability(id, name, Groups.NETWORK, status,
                requiredPermissions = listOf(if (needsConnect) Perms.BT_CONNECT else Perms.BT_SCAN))
        }
        out.add(btCap("bluetooth", com.vicinityprobe.model.Labels.BLUETOOTH, needsConnect = false))
        out.add(btCap("bt_paired", com.vicinityprobe.model.Labels.BT_PAIRED, needsConnect = true))

        val audioOk = ContextCompat.checkSelfPermission(ctx, Perms.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out.add(Capability("noise", com.vicinityprobe.model.Labels.NOISE, Groups.AUDIO,
            if (audioOk) CapabilityStatus.SUPPORTED else CapabilityStatus.PERMISSION_MISSING,
            requiredPermissions = listOf(Perms.RECORD_AUDIO)))
        out.add(Capability("audio_state", com.vicinityprobe.model.Labels.AUDIO_STATE, Groups.AUDIO, CapabilityStatus.SUPPORTED))
        out.add(Capability("battery", com.vicinityprobe.model.Labels.BATTERY, Groups.BATTERY, CapabilityStatus.SUPPORTED))
        out.add(Capability("device", com.vicinityprobe.model.Labels.DEVICE_INFO, Groups.DEVICE, CapabilityStatus.SUPPORTED))
        out.add(Capability("system", com.vicinityprobe.model.Labels.SYSTEM, Groups.DEVICE, CapabilityStatus.SUPPORTED))

        return out.sortedBy { Groups.ordered.indexOf(it.group) }
    }

    fun supportedCount(caps: List<Capability>): Int = caps.count { it.status == CapabilityStatus.SUPPORTED }
}
