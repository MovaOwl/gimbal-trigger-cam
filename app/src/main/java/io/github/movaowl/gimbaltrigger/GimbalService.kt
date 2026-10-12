package io.github.movaowl.gimbaltrigger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.UUID

/**
 * Фоновый сервис: держит связь с гимбалом, ловит удержание курка
 * и выполняет выбранное действие через Shizuku.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class GimbalService : Service() {

    companion object {
        private const val CHANNEL = "gimbal"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "io.github.movaowl.gimbaltrigger.STOP"
        private val SVC: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_CHAR: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile
        var running = false
            private set

        fun start(c: Context) {
            c.startForegroundService(Intent(c, GimbalService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, GimbalService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var ready = false
    private var stopping = false
    private var lastFrameAt = 0L
    private var lastFireAt = 0L
    private var attempt = 0

    private val parser = FrameParser { onFrame(it) }
    private val detector = HoldDetector(
        onPress = { onTriggerPress() },
        onRelease = { onTriggerRelease(it) },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotification("Подключение…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, buildNotification("Подключение…"))
            }
        } catch (e: Exception) {
            Bus.event("Не удалось запустить сервис: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (gatt == null) connect()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 3000)
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        handler.removeCallbacksAndMessages(null)
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        Bus.state("Сервис остановлен")
        Bus.event("Сервис остановлен")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ уведомление

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Курок гимбала", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GimbalService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Курок гимбала")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Стоп", stop
                ).build()
            )
            .build()
    }

    private fun setState(s: String) {
        Bus.state(s)
        if (!stopping) {
            try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s)) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ связь

    private fun connect() {
        if (stopping) return
        val addr = Prefs.address(this)
        if (addr == null) {
            setState("Гимбал не выбран — выберите его в приложении")
            stopSelf()
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setState("Bluetooth выключен, жду…")
            scheduleReconnect(5000)
            return
        }
        ready = false
        parser.reset()
        detector.reset()
        attempt++
        connectStartedAt = SystemClock.elapsedRealtime()
        setState(if (attempt == 1) "Подключение к гимбалу…" else "Переподключение (попытка $attempt)…")
        try {
            val dev = adapter.getRemoteDevice(addr)
            gatt = dev.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Bus.event("Ошибка подключения: ${e.message}")
            scheduleReconnect(5000)
        }
    }

    private fun scheduleReconnect(ms: Long) {
        if (stopping) return
        handler.postDelayed({ if (!stopping && gatt == null) connect() }, ms)
    }

    private fun dropConnection(reason: String) {
        val g = gatt ?: return
        Bus.event(reason)
        gatt = null
        ready = false
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
        setState("Связь потеряна, переподключаюсь…")
        scheduleReconnect(2000)
    }

    /** Если кадры перестали приходить или подключение зависло — переподключаемся. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopping) return
            val now = SystemClock.elapsedRealtime()
            if (gatt != null && ready && now - lastFrameAt > 6000) dropConnection("Гимбал замолчал")
            else if (gatt != null && !ready && now - connectStartedAt > 20000) dropConnection("Подключение зависло")
            handler.postDelayed(this, 3000)
        }
    }
    private var connectStartedAt = 0L

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) { try { g.close() } catch (_: Exception) {}; return@post }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        setState("Связь есть, настраиваю…")
                        handler.postDelayed({ if (g === gatt) g.discoverServices() }, 300)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> dropConnection("Гимбал отключился (код $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val c = g.getService(SVC)?.getCharacteristic(NOTIFY_CHAR)
                if (c == null) {
                    dropConnection("У устройства нет канала FF02 — это точно гимбал?")
                    return@post
                }
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CCCD)
                if (d == null) {
                    dropConnection("Нет дескриптора подписки")
                    return@post
                }
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) else { d.value = v; g.writeDescriptor(d) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                ready = true
                attempt = 0
                lastFrameAt = SystemClock.elapsedRealtime()
                setState("Готов · зажмите курок")
                Bus.event("Гимбал подключён")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            handler.post { if (g === gatt) parser.feed(v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: return
            handler.post { if (g === gatt) parser.feed(v) }
        }
    }

    // ------------------------------------------------------------------ курок

    private fun onFrame(f: ByteArray) {
        val mode = FrameParser.modeOf(f) ?: return
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        detector.onMode(mode, now)
    }

    private fun onTriggerPress() {
        if (!Prefs.fireOnRelease(this)) {
            Bus.event("Курок зажат")
            fire(Prefs.pressAction(this))
        }
    }

    private fun onTriggerRelease(ms: Long) {
        if (!Prefs.fireOnRelease(this)) return
        val long = ms >= Prefs.thresholdMs(this)
        Bus.event("Курок отпущен: ${ms} мс (${if (long) "длинное" else "короткое"})")
        fire(if (long) Prefs.longAction(this) else Prefs.shortAction(this))
    }

    private fun fire(a: GAction) {
        if (a == GAction.NONE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFireAt < 400) return
        lastFireAt = now
        if (Prefs.vibrate(this)) vibrate()
        ShizukuShell.runAction(a) { ok, msg ->
            Bus.event(if (ok) "→ ${a.title}" else "✗ ${a.title}: $msg")
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }
}
