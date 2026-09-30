package sk.firesport.cam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.util.Locale

/** Batéria, teplota, voľné miesto a mikrofón. */
class DeviceStatus(context: Context) {
    private val ctx = context.applicationContext

    var batteryPct = -1
        private set
    var charging = false
        private set
    var temperatureC = Float.NaN
        private set
    var freeBytes = 0L
        private set
    var minutesLeft = -1L
        private set
    var thermal = 0
        private set
    var micLabel = "vstavaný"
        private set
    var warnings: List<String> = emptyList()
        private set

    /** Odhad dátového toku videa v bitoch za sekundu. */
    private fun estimateBps(p: SharedPreferences): Long {
        val mbps = Prefs.int(p, "video_bitrate", 0)
        val video = if (mbps > 0) mbps * 1_000_000L else when (p.getString("video_quality", "FHD")) {
            "UHD", "HIGHEST" -> 45_000_000L
            "FHD" -> 17_000_000L
            "HD" -> 10_000_000L
            else -> 4_000_000L
        }
        return video + 256_000L
    }

    fun update(p: SharedPreferences) {
        try {
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) {
                val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                val t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                temperatureC = if (t != Int.MIN_VALUE) t / 10f else Float.NaN
            }
        } catch (_: Exception) {
        }
        try {
            freeBytes = StatFs(VideoStore.root(ctx).absolutePath).availableBytes
            minutesLeft = freeBytes * 8 / estimateBps(p) / 60
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                thermal = pm.currentThermalStatus
            } catch (_: Exception) {
            }
        }
        micLabel = detectMic()

        val w = ArrayList<String>()
        if (batteryPct in 0..14 && !charging) w.add("slabá batéria $batteryPct %")
        if (minutesLeft in 0..9) w.add("málo miesta (~$minutesLeft min)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            w.add("telefón sa prehrieva")
        } else if (!temperatureC.isNaN() && temperatureC >= 45f) {
            w.add("batéria je horúca")
        }
        warnings = w
    }

    private fun detectMic(): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            when {
                inputs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } -> "USB"
                inputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "káblový"
                inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "Bluetooth"
                else -> "vstavaný"
            }
        } catch (_: Exception) {
            "vstavaný"
        }
    }

    fun summary(): String {
        val sb = StringBuilder()
        if (batteryPct >= 0) sb.append("🔋").append(batteryPct).append('%').append(if (charging) "⚡" else "")
        if (!temperatureC.isNaN()) sb.append(' ').append(String.format(Locale.US, "%.0f°C", temperatureC))
        sb.append(" • 💾 ").append(formatBytes(freeBytes))
        if (minutesLeft >= 0) sb.append(" (~").append(formatMinutes(minutesLeft)).append(')')
        sb.append(" • 🎤 ").append(micLabel)
        return sb.toString()
    }

    companion object {
        fun formatBytes(b: Long): String = when {
            b >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", b / 1e9)
            else -> String.format(Locale.US, "%.0f MB", b / 1e6)
        }

        fun formatMinutes(m: Long): String = if (m >= 120) "${m / 60} h" else "$m min"
    }
}
