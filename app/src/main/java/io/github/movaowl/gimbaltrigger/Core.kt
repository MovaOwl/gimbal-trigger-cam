package io.github.movaowl.gimbaltrigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BuildInfo {
    const val VERSION = "1.2"
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

/**
 * Протокол Feiyu «AK» (см. docs/PROTOCOL.md):
 * A5 5A · источник · получатель · команда (2 байта LE: тип<<13 | id) · msgId · длина N (2 байта LE) · данные · CRC16 (LE).
 * CRC-16/XModem считается по байтам от «источника» до конца данных.
 */
object Ak {
    const val SRC_APP = 3

    fun crc(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) {
            c = c xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    fun frame(target: Int, type: Int, id: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val cmd = (type shl 13) or id
        val b = ByteArray(data.size + 11)
        b[0] = 0xA5.toByte(); b[1] = 0x5A
        b[2] = SRC_APP.toByte(); b[3] = target.toByte()
        b[4] = (cmd and 0xFF).toByte(); b[5] = (cmd shr 8).toByte()
        b[6] = 0
        b[7] = (data.size and 0xFF).toByte(); b[8] = (data.size shr 8).toByte()
        data.copyInto(b, 9)
        val c = crc(b, 2, 9 + data.size)
        b[9 + data.size] = (c and 0xFF).toByte(); b[10 + data.size] = (c shr 8).toByte()
        return b
    }

    /** «Приложение на экране» — после этого модуль кнопок присылает события кнопок (команда 80). */
    fun appStatus(foreground: Boolean) = frame(4, 0, 114, byteArrayOf(if (foreground) 1 else 0, 0, 4, 0))

    fun cmdId(f: ByteArray) = if (f.size >= 6) ((f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8)) and 0x1FFF else -1
    fun cmdType(f: ByteArray) = if (f.size >= 6) (f[5].toInt() and 0xFF) shr 5 else -1
    fun data(f: ByteArray): ByteArray = if (f.size >= 11) f.copyOfRange(9, f.size - 2) else ByteArray(0)
    fun crcOk(f: ByteArray) = f.size >= 11 &&
        crc(f, 2, f.size - 2) == ((f[f.size - 2].toInt() and 0xFF) or ((f[f.size - 1].toInt() and 0xFF) shl 8))

    val CMD_NAMES = mapOf(
        14 to "джойстик", 22 to "состояние кнопок", 24 to "режим камеры", 30 to "состояние гимбала",
        32 to "польз. параметры", 31 to "сист. параметры", 33 to "инфо об устройстве", 38 to "версия прошивки",
        49 to "сброс настроек", 50 to "режим следования", 51 to "режим использования", 63 to "снимок/камера",
        73 to "USB-камера", 79 to "ошибка", 80 to "СОБЫТИЕ КНОПКИ", 83 to "селфи", 93 to "поворот на угол",
        97 to "сброс положения", 113 to "управление движением", 114 to "статус приложения",
        115 to "поворот относит.", 119 to "предупреждение", 153 to "пульс",
    )
    val TYPE_NAMES = listOf("push", "set", "get", "ответ set", "ответ get")
    val KEY_NAMES = mapOf(
        0 to "ФОТО", 1 to "ВИДЕО", 2 to "смена режима камеры", 3 to "смена камеры", 4 to "настройки камеры",
        5 to "зум/параметр", 6 to "меню", 7 to "джойстик", 9 to "серийная съёмка", 10 to "умное слежение",
        11 to "фиксация по центру", 12 to "альбом", 13 to "история", 14 to "фокус", 15 to "фоллоу-фокус",
        16 to "зум", 17 to "слежение за лицом", 18 to "широкий угол", 19 to "панорама 180", 20 to "панорама 360",
        21 to "таймлапс по пути", 22 to "видео 2",
    )

    fun describe(f: ByteArray): String {
        val id = cmdId(f)
        val type = cmdType(f)
        val name = CMD_NAMES[id] ?: "cmd $id"
        val t = TYPE_NAMES.getOrNull(type) ?: "тип $type"
        val src = f[2].toInt() and 0xFF
        val dst = f[3].toInt() and 0xFF
        var s = "$name · $t · $src→$dst"
        if (id == 80) {
            val d = data(f)
            if (d.isNotEmpty()) {
                val k = d[0].toInt() and 0xFF
                s += " · ${KEY_NAMES[k] ?: "кнопка $k"} " + d.drop(1).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            }
        }
        if (!crcOk(f)) s += " · CRC ✗"
        return s
    }
}

/** Склеивает пакеты BLE в кадры протокола Feiyu (полная длина = N + 11). */
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
            if (buf.size < 9) return
            val len = (buf[7].toInt() and 0xFF) or ((buf[8].toInt() and 0xFF) shl 8)
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
