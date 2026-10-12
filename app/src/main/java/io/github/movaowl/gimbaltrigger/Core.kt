package io.github.movaowl.gimbaltrigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BuildInfo {
    const val VERSION = "1.0"
}

/** Что делать по курку. Все действия выполняются через Shizuku (права ADB). */
enum class GAction(val title: String, val shell: String?) {
    NONE("Ничего", null),
    SHUTTER("Спуск камеры (громкость +)", "input keyevent 24"),
    VOLUME_DOWN("Громкость −", "input keyevent 25"),
    CAMERA_KEY("Аппаратная кнопка камеры", "input keyevent 27"),
    OPEN_PHOTO("Открыть камеру: фото", "am start -a android.media.action.STILL_IMAGE_CAMERA"),
    OPEN_VIDEO("Открыть камеру: видео", "am start -a android.media.action.VIDEO_CAMERA"),
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun address(c: Context): String? = p(c).getString("addr", null)
    fun deviceName(c: Context): String? = p(c).getString("name", null)
    fun setDevice(c: Context, addr: String, name: String?) =
        p(c).edit().putString("addr", addr).putString("name", name).apply()

    /** false — срабатывать сразу при зажатии курка; true — при отпускании (короткое/длинное). */
    fun fireOnRelease(c: Context) = p(c).getBoolean("onRelease", false)
    fun setFireOnRelease(c: Context, v: Boolean) = p(c).edit().putBoolean("onRelease", v).apply()

    private fun action(c: Context, key: String, def: GAction): GAction =
        try { GAction.valueOf(p(c).getString(key, def.name)!!) } catch (_: Exception) { def }

    fun pressAction(c: Context) = action(c, "pressAction", GAction.SHUTTER)
    fun shortAction(c: Context) = action(c, "shortAction", GAction.SHUTTER)
    fun longAction(c: Context) = action(c, "longAction", GAction.SHUTTER)
    fun setAction(c: Context, key: String, a: GAction) = p(c).edit().putString(key, a.name).apply()

    fun thresholdMs(c: Context) = p(c).getInt("threshold", 1200)
    fun setThresholdMs(c: Context, v: Int) = p(c).edit().putInt("threshold", v).apply()

    fun vibrate(c: Context) = p(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = p(c).edit().putBoolean("vibrate", v).apply()
}

/** Простая связь сервиса с экраном (один процесс). Всё на главном потоке. */
object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val events = ArrayDeque<String>()
    var state: String = "Сервис остановлен"
        private set
    var listener: (() -> Unit)? = null

    fun state(s: String) = main.post {
        state = s
        listener?.invoke()
    }

    fun event(s: String) = main.post {
        events.addLast("${fmt.format(Date())}  $s")
        while (events.size > 40) events.removeFirst()
        listener?.invoke()
    }
}

/** Склеивает пакеты BLE в кадры протокола Feiyu: A5 5A, тип (4), длина (2, BE), данные, 3 байта. */
class FrameParser(private val onFrame: (ByteArray) -> Unit) {
    private var buf = ByteArray(0)

    fun reset() {
        buf = ByteArray(0)
    }

    private fun isHeader(b: ByteArray, i: Int) =
        i + 1 < b.size && b[i] == 0xA5.toByte() && b[i + 1] == 0x5A.toByte()

    fun feed(v: ByteArray) {
        if (buf.isNotEmpty() && isHeader(v, 0)) buf = ByteArray(0)
        buf += v
        while (true) {
            var start = -1
            for (i in 0 until buf.size - 1) if (isHeader(buf, i)) { start = i; break }
            if (start < 0) { buf = ByteArray(0); return }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 8) return
            val len = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if (len > 1024) { buf = buf.copyOfRange(2, buf.size); continue }
            val total = len + 11
            if (buf.size < total) return
            onFrame(buf.copyOfRange(0, total))
            buf = buf.copyOfRange(total, buf.size)
        }
    }

    companion object {
        /** Байт режима из кадра телеметрии [00 FF 1E 00], или null. 01 — обычный, 03 — Lock (курок зажат). */
        fun modeOf(f: ByteArray): Int? {
            if (f.size < 10) return null
            if (f[2] != 0x00.toByte() || f[3] != 0xFF.toByte() || f[4] != 0x1E.toByte() || f[5] != 0x00.toByte()) return null
            return f[9].toInt() and 0xFF
        }
    }
}

/**
 * Ловит удержание курка по смене режима гимбала:
 * обычный режим → Lock (03) — курок зажат; Lock → тот же режим — отпущен.
 * Смена режима на что-то другое (FPV и т. п.) просто запоминается как новый обычный режим.
 */
class HoldDetector(
    private val onPress: () -> Unit,
    private val onRelease: (durationMs: Long) -> Unit,
) {
    companion object {
        const val LOCK = 0x03
        private const val MAX_HOLD_MS = 20_000L
    }

    private var stableMode = -1
    private var held = false
    private var holdStart = 0L

    fun reset() {
        stableMode = -1
        held = false
    }

    fun onMode(mode: Int, now: Long) {
        if (!held) {
            when {
                stableMode == -1 -> stableMode = mode
                mode == stableMode -> {}
                mode == LOCK -> { held = true; holdStart = now; onPress() }
                else -> stableMode = mode
            }
        } else {
            if (mode == LOCK) {
                if (now - holdStart > MAX_HOLD_MS) { held = false; stableMode = LOCK } // режим Lock включён надолго
                return
            }
            held = false
            val returned = mode == stableMode
            stableMode = mode
            if (returned) onRelease(now - holdStart)
        }
    }
}
