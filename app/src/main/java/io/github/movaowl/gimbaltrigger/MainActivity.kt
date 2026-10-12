package io.github.movaowl.gimbaltrigger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * «Разведчик»: подключается к гимбалу по BLE, подписывается на все каналы
 * уведомлений и пишет в журнал всё, что гимбал присылает. Ничего не меняет
 * в гимбале, пока вы сами не отправите байты через поле «Запись».
 */
@SuppressLint("MissingPermission", "SetTextI18n")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {

    companion object {
        private const val APP_VERSION = "0.1-scout"
        private const val REQ_PERMS = 1
        private const val MAX_SHOWN_LINES = 400
        private const val SCAN_MS = 15_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val NAME_HINT = Regex("fy|feiyu|scorp|f2c|gimbal", RegexOption.IGNORE_CASE)
        private val MARK_PRESETS = listOf(
            "курок: одно нажатие",
            "курок: двойное нажатие",
            "курок: тройное нажатие",
            "курок: удержание",
            "кнопка фото/спуска на ручке",
            "кнопка FPV",
            "кнопка A/B",
            "кнопка режима/Lock",
            "колесо/диск",
            "ничего не трогаю (фон)",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // UI
    private lateinit var statusView: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var devicesScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var dedupeBox: CheckBox
    private lateinit var writeSpinner: Spinner
    private lateinit var writeInput: EditText

    // Bluetooth
    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var scanning = false
    private val found = LinkedHashMap<String, Found>()
    private var renderPending = false

    private class Found(val device: BluetoothDevice, var name: String?, var rssi: Int, val paired: Boolean)

    private var gatt: BluetoothGatt? = null
    private val notifyChars = mutableListOf<BluetoothGattCharacteristic>()
    private val writeChars = mutableListOf<BluetoothGattCharacteristic>()
    private val muted = mutableSetOf<String>()
    private val mutedCount = HashMap<String, Int>()
    private val lastPayload = HashMap<String, String>()
    private val repeatCount = HashMap<String, Int>()

    // Журнал
    private val fullLog = StringBuilder()
    private val shownLines = kotlin.collections.ArrayDeque<String>()
    private var logRefreshPending = false

    // Очередь GATT-операций (Android выполняет их строго по одной)
    private val ops = kotlin.collections.ArrayDeque<Pair<String, () -> Boolean>>()
    private var opBusy = false
    private var opSeq = 0

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        log("Gimbal Scout $APP_VERSION · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("1) Закройте официальное приложение Feiyu. 2) Включите гимбал. 3) «Сканировать».")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        statusView = TextView(this).apply {
            text = "Не подключено"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusView)

        root.addView(row(
            button("Сканировать") { startScan() },
            button("Отключить") { disconnect() },
        ))

        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devicesScroll = ScrollView(this).apply {
            addView(devicesBox)
            visibility = View.GONE
        }
        root.addView(devicesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))

        root.addView(row(
            button("Метка") { showMarkDialog() },
            button("Шумные каналы") { showMuteDialog() },
        ))
        root.addView(row(
            button("Поделиться") { shareLog() },
            button("Копировать") { copyLog() },
            button("Очистить") { clearLog() },
        ))

        dedupeBox = CheckBox(this).apply {
            text = "Сворачивать одинаковые повторы"
            isChecked = true
        }
        root.addView(dedupeBox)

        writeSpinner = Spinner(this)
        writeInput = EditText(this).apply {
            hint = "HEX, напр. A5 5A 01"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
        }
        val writeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(writeSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(writeInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
            addView(button("Запись") { sendWrite() })
        }
        root.addView(writeRow)
        updateWriteSpinner()

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.argb(30, 128, 128, 128))
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    // ------------------------------------------------------------------ журнал

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        fullLog.append(line).append('\n')
        shownLines.addLast(line)
        while (shownLines.size > MAX_SHOWN_LINES) shownLines.removeFirst()
        if (!logRefreshPending) {
            logRefreshPending = true
            handler.postDelayed({
                logRefreshPending = false
                logView.text = shownLines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }, 120)
        }
    }

    private fun clearLog() {
        fullLog.setLength(0)
        shownLines.clear()
        lastPayload.clear()
        repeatCount.clear()
        mutedCount.clear()
        logView.text = ""
    }

    private fun saveLogFile(): File? = try {
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = File(dir, "scout-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
        f.writeText(fullLog.toString())
        f
    } catch (e: Exception) {
        null
    }

    private fun logForExport(): String {
        flushRepeats()
        val summary = if (mutedCount.isEmpty()) "" else
            "\n--- заглушено: " + mutedCount.entries.joinToString { "${it.key}=${it.value}" } + "\n"
        val text = fullLog.toString() + summary
        val limit = 180_000
        return if (text.length > limit) "…(обрезано начало)…\n" + text.takeLast(limit) else text
    }

    private fun shareLog() {
        val file = saveLogFile()
        if (file != null) log("Журнал сохранён: ${file.absolutePath}")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Gimbal Scout log")
            putExtra(Intent.EXTRA_TEXT, logForExport())
        }
        startActivity(Intent.createChooser(send, "Отправить журнал"))
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Gimbal Scout log", logForExport()))
        Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun showMarkDialog() {
        val items = (MARK_PRESETS + "Свой текст…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Что сейчас нажимаете?")
            .setItems(items) { _, which ->
                if (which < MARK_PRESETS.size) mark(MARK_PRESETS[which]) else showCustomMarkDialog()
            }
            .show()
    }

    private fun showCustomMarkDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Метка")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> mark(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun mark(text: String) {
        flushRepeats()
        lastPayload.clear() // после метки показываем первое сообщение каждого канала заново
        log("========== МЕТКА: $text ==========")
    }

    private fun showMuteDialog() {
        if (notifyChars.isEmpty()) {
            Toast.makeText(this, "Сначала подключитесь к гимбалу", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = notifyChars.map { label(it) }.toTypedArray()
        val keys = notifyChars.map { key(it) }
        val checked = BooleanArray(keys.size) { keys[it] in muted }
        AlertDialog.Builder(this)
            .setTitle("Не показывать каналы")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("OK") { _, _ ->
                muted.clear()
                keys.forEachIndexed { i, k -> if (checked[i]) muted.add(k) }
                log("Заглушено каналов: ${muted.size}")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------------------ разрешения

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPerms(): Boolean = Build.VERSION.SDK_INT < 23 ||
        neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            log("! Без разрешений Bluetooth приложение не сможет найти гимбал")
        }
    }

    // ------------------------------------------------------------------ сканирование

    private val stopScanRunnable = Runnable { stopScan() }

    private fun startScan() {
        if (!hasPerms()) {
            requestPermissions(neededPerms(), REQ_PERMS)
            return
        }
        val a = btAdapter ?: run { log("! На телефоне нет Bluetooth"); return }
        if (!a.isEnabled) {
            log("Bluetooth выключен — включите его")
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            return
        }
        val scanner = a.bluetoothLeScanner ?: run { log("! BLE-сканер недоступен"); return }
        if (scanning) stopScan()

        found.clear()
        a.bondedDevices?.forEach { found[it.address] = Found(it, it.name, 0, paired = true) }
        devicesScroll.visibility = View.VISIBLE
        renderDevices()

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            setStatus("Поиск… (15 с)")
            log("Поиск устройств…")
            handler.removeCallbacks(stopScanRunnable)
            handler.postDelayed(stopScanRunnable, SCAN_MS)
        } catch (e: Exception) {
            log("! Ошибка запуска поиска: ${e.message}")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        try { btAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (gatt == null) setStatus("Поиск завершён — выберите устройство")
        if (found.none { !it.value.paired } && Build.VERSION.SDK_INT < 31) {
            log("Ничего не найдено? На Android 11 и ниже для поиска нужна включённая геолокация.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: try { dev.name } catch (_: Exception) { null }
            val rssi = result.rssi
            handler.post {
                val f = found[dev.address]
                if (f == null) {
                    found[dev.address] = Found(dev, name, rssi, paired = false)
                } else {
                    if (name != null) f.name = name
                    f.rssi = rssi
                }
                scheduleRender()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { log("! Поиск не удался, код $errorCode"); scanning = false }
        }
    }

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        handler.postDelayed({ renderPending = false; renderDevices() }, 400)
    }

    private fun renderDevices() {
        devicesBox.removeAllViews()
        val sorted = found.values.sortedWith(
            compareByDescending<Found> { it.name?.let { n -> NAME_HINT.containsMatchIn(n) } == true }
                .thenByDescending { it.name != null }
                .thenByDescending { if (it.paired && it.rssi == 0) -200 else it.rssi }
        )
        for (f in sorted) {
            val star = if (f.name?.let { NAME_HINT.containsMatchIn(it) } == true) "★ " else ""
            val rssi = if (f.rssi != 0) "  ${f.rssi} dBm" else ""
            val paired = if (f.paired) "  (сопряжено)" else ""
            devicesBox.addView(button("$star${f.name ?: "без имени"}\n${f.device.address}$rssi$paired") {
                connect(f.device)
            }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL })
        }
    }

    // ------------------------------------------------------------------ подключение

    private fun connect(device: BluetoothDevice) {
        stopScan()
        devicesScroll.visibility = View.GONE
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        resetQueue()
        notifyChars.clear()
        writeChars.clear()
        updateWriteSpinner()
        val name = try { device.name } catch (_: Exception) { null }
        log("== Подключаюсь к ${name ?: "?"} ${device.address}")
        setStatus("Подключение…")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private fun disconnect() {
        stopScan()
        val g = gatt ?: return
        log("== Отключаюсь")
        g.disconnect()
        handler.postDelayed({
            if (gatt === g) {
                g.close()
                gatt = null
                setStatus("Отключено")
            }
        }, 1500)
    }

    override fun onDestroy() {
        stopScan()
        gatt?.close()
        gatt = null
        super.onDestroy()
    }

    private fun ui(block: () -> Unit) {
        handler.post(block)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = ui {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("== Подключено (status=$status), ищу сервисы…")
                    setStatus("Подключено, читаю устройство…")
                    handler.postDelayed({ if (gatt === g) g.discoverServices() }, 600)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("== Отключено (status=$status)")
                    setStatus("Отключено")
                    resetQueue()
                    if (gatt === g) {
                        g.close()
                        gatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = ui { onDiscovered(g, status) }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = ui {
            log("MTU = $mtu (status=$status)")
            opDone()
        }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val v = value.copyOf()
            ui { onRead(c, v, status) }
        }

        // Android 12 и ниже
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: ByteArray(0)
            ui { onRead(c, v, status) }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.copyOf()
            ui { onNotify(c, v) }
        }

        // Android 12 и ниже
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val v = c.value?.copyOf() ?: ByteArray(0)
            ui { onNotify(c, v) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = ui {
            log("→ запись в ${label(c)}: status=$status")
            opDone()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = ui {
            log("   подписка ${label(d.characteristic)}: ${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "ошибка $status"}")
            opDone()
        }
    }

    private fun onDiscovered(g: BluetoothGatt, status: Int) {
        if (gatt !== g) return
        if (status != BluetoothGatt.GATT_SUCCESS) log("! discoverServices status=$status")
        notifyChars.clear()
        writeChars.clear()
        val readable = mutableListOf<BluetoothGattCharacteristic>()

        log("== Карта устройства:")
        for (s in g.services) {
            log("СЕРВИС ${s.uuid}")
            for (c in s.characteristics) {
                log("  ХАР ${c.uuid} [${props(c.properties)}] #${c.instanceId}")
                for (d in c.descriptors) log("    дескр ${d.uuid}")
                val p = c.properties
                if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) notifyChars.add(c)
                if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) writeChars.add(c)
                if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) readable.add(c)
            }
        }
        updateWriteSpinner()

        if (Build.VERSION.SDK_INT >= 21) enqueue("MTU") { g.requestMtu(247) }
        for (c in readable) enqueue("чтение ${label(c)}") { g.readCharacteristic(c) }
        for (c in notifyChars) enqueue("подписка ${label(c)}") { subscribe(g, c) }
        enqueue("готово") {
            log("== Готово: подписан на ${notifyChars.size} канал(ов). Ставьте «Метку» и нажимайте кнопки на гимбале.")
            setStatus("Слушаю гимбал · каналов: ${notifyChars.size}")
            false
        }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val okLocal = g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD)
        if (d == null) {
            log("   ${label(c)}: нет дескриптора 2902 (локально ${if (okLocal) "OK" else "ошибка"})")
            return false
        }
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    private fun onRead(c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
        log("ЧТЕНИЕ ${label(c)} st=$status [${v.size}] ${hex(v)}${ascii(v)}")
        opDone()
    }

    private fun onNotify(c: BluetoothGattCharacteristic, v: ByteArray) {
        val k = key(c)
        if (k in muted) {
            mutedCount[label(c)] = (mutedCount[label(c)] ?: 0) + 1
            return
        }
        val h = hex(v)
        if (dedupeBox.isChecked && lastPayload[k] == h) {
            repeatCount[k] = (repeatCount[k] ?: 0) + 1
            return
        }
        repeatCount.remove(k)?.let { if (it > 0) log("   … ${label(c)}: предыдущее повторилось ещё ×$it") }
        lastPayload[k] = h
        log("← ${label(c)} [${v.size}] $h${ascii(v)}")
    }

    private fun flushRepeats() {
        val labels = notifyChars.associateBy({ key(it) }, { label(it) })
        for ((k, n) in repeatCount) if (n > 0) log("   … ${labels[k] ?: k}: предыдущее повторилось ещё ×$n")
        repeatCount.clear()
    }

    // ------------------------------------------------------------------ запись

    private fun updateWriteSpinner() {
        val items = if (writeChars.isEmpty()) listOf("— нет —") else writeChars.map { label(it) }
        writeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun sendWrite() {
        val g = gatt
        val idx = writeSpinner.selectedItemPosition
        if (g == null || idx !in writeChars.indices) {
            Toast.makeText(this, "Нет подключения или канала для записи", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = parseHex(writeInput.text.toString()) ?: run {
            Toast.makeText(this, "Неверный HEX", Toast.LENGTH_SHORT).show()
            return
        }
        val c = writeChars[idx]
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        log("→ ${label(c)} [${bytes.size}] ${hex(bytes)}")
        enqueue("запись ${label(c)}") {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = bytes
                g.writeCharacteristic(c)
            }
        }
    }

    // ------------------------------------------------------------------ очередь

    private fun enqueue(name: String, op: () -> Boolean) {
        ops.addLast(name to op)
        next()
    }

    private fun next() {
        while (!opBusy) {
            val (name, op) = ops.removeFirstOrNull() ?: return
            val started = try {
                op()
            } catch (e: Exception) {
                log("! $name: ${e.message}")
                false
            }
            if (started) {
                opBusy = true
                val seq = ++opSeq
                handler.postDelayed({
                    if (opBusy && opSeq == seq) {
                        log("! нет ответа: $name")
                        opDone()
                    }
                }, 4000)
            }
        }
    }

    private fun opDone() {
        opBusy = false
        opSeq++
        next()
    }

    private fun resetQueue() {
        ops.clear()
        opBusy = false
        opSeq++
    }

    // ------------------------------------------------------------------ форматирование

    private fun shortUuid(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8).uppercase()
        else s.substring(0, 8).uppercase()
    }

    private fun label(c: BluetoothGattCharacteristic): String {
        val svc = c.service?.uuid?.let { shortUuid(it) } ?: "?"
        return "$svc/${shortUuid(c.uuid)}"
    }

    private fun key(c: BluetoothGattCharacteristic): String =
        "${c.service?.uuid}/${c.uuid}/${c.instanceId}"

    private fun props(p: Int): String {
        val out = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) out += "чтение"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out += "запись"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out += "запись-без-ответа"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out += "уведомл"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out += "индик"
        return out.joinToString(",")
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun ascii(b: ByteArray): String {
        if (b.size < 3) return ""
        val printable = b.all { it in 0x20..0x7E }
        return if (printable) "  \"" + String(b, Charsets.US_ASCII) + "\"" else ""
    }

    private fun parseHex(s: String): ByteArray? {
        val clean = s.replace(Regex("[\\s,:]|0x", RegexOption.IGNORE_CASE), "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
