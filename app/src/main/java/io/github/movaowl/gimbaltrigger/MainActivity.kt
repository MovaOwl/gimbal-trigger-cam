package io.github.movaowl.gimbaltrigger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1
        private val NAME_HINT = Regex("fy|feiyu|scorp|f2c|gimbal", RegexOption.IGNORE_CASE)
        private val THRESHOLDS = listOf(600, 800, 1000, 1200, 1500, 2000)
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button
    private lateinit var deviceStatus: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var serviceButton: Button
    private lateinit var pressRow: View
    private lateinit var shortRow: View
    private lateinit var longRow: View
    private lateinit var thresholdRow: View
    private lateinit var eventsView: TextView

    private var pendingStart = false
    private var scanning = false

    private val shizukuResult = Shizuku.OnRequestPermissionResultListener { _, _ -> handler.post { refresh() } }
    private val shizukuBinder = Shizuku.OnBinderReceivedListener { handler.post { refresh() } }
    private val shizukuDead = Shizuku.OnBinderDeadListener { handler.post { refresh() } }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuResult)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
            Shizuku.addBinderDeadListener(shizukuDead)
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        Bus.listener = null
        stopScan()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuResult)
            Shizuku.removeBinderReceivedListener(shizukuBinder)
            Shizuku.removeBinderDeadListener(shizukuDead)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun label(text: String = "") = TextView(this).apply {
        this.text = text
        textSize = 14f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun spinnerRow(title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(label(title).apply { setPadding(0, dp(8), 0, 0) })
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, items.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        row.addView(sp)
        return row
    }

    private fun actionRow(title: String, key: String, current: GAction): View {
        val actions = GAction.values().toList()
        return spinnerRow(title, actions.map { it.title }, actions.indexOf(current)) {
            Prefs.setAction(this, key, actions[it])
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Курок гимбала → камера"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(label("Feiyu Scorp-C · v${BuildInfo.VERSION}. Кнопка ⇄ или удержание курка нажимает спуск стандартной камеры."))

        // 1. Shizuku
        root.addView(header("1. Shizuku"))
        shizukuStatus = label()
        root.addView(shizukuStatus)
        shizukuButton = button("Дать разрешение") { onShizukuButton() }
        root.addView(shizukuButton)

        // 2. Гимбал
        root.addView(header("2. Гимбал"))
        deviceStatus = label()
        root.addView(deviceStatus)
        root.addView(button("Найти гимбал") { startScan() })
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        // 3. Сервис
        root.addView(header("3. Работа в фоне"))
        serviceStatus = label()
        root.addView(serviceStatus)
        serviceButton = button("Запустить") { toggleService() }
        root.addView(serviceButton)
        root.addView(label("Официальное приложение Feiyu должно быть закрыто — гимбал держит одно подключение.").apply {
            textSize = 12f
        })

        // Кнопка ⇄
        root.addView(header("Кнопка ⇄ (под большим пальцем)"))
        root.addView(label("Одиночное нажатие кнопки со стрелками ⇄ рядом с FPV.").apply { textSize = 12f })
        root.addView(actionRow("Действие по кнопке ⇄", "knobAction", Prefs.knobButtonAction(this)))
        root.addView(CheckBox(this).apply {
            text = "Не менять функцию колеса (возвращать после нажатия)"
            isChecked = Prefs.keepKnob(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setKeepKnob(this@MainActivity, v) }
        })

        // Настройки курка
        root.addView(header("Курок (удержание)"))
        val modes = listOf("Сразу, как только курок зажат", "При отпускании: короткое / длинное")
        root.addView(spinnerRow("Когда срабатывать", modes, if (Prefs.fireOnRelease(this)) 1 else 0) {
            Prefs.setFireOnRelease(this, it == 1)
            updateModeRows()
        })
        pressRow = actionRow("Действие при зажатии", "pressAction", Prefs.pressAction(this))
        shortRow = actionRow("Короткое удержание", "shortAction", Prefs.shortAction(this))
        longRow = actionRow("Длинное удержание", "longAction", Prefs.longAction(this))
        val th = Prefs.thresholdMs(this)
        thresholdRow = spinnerRow(
            "Граница «короткое / длинное»",
            THRESHOLDS.map { "%.1f с".format(it / 1000.0) },
            THRESHOLDS.indexOf(th).let { if (it < 0) 3 else it },
        ) { Prefs.setThresholdMs(this, THRESHOLDS[it]) }
        root.addView(pressRow)
        root.addView(shortRow)
        root.addView(longRow)
        root.addView(thresholdRow)
        root.addView(CheckBox(this).apply {
            text = "Вибрация при срабатывании"
            isChecked = Prefs.vibrate(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setVibrate(this@MainActivity, v) }
        })
        updateModeRows()

        root.addView(button("Проверить действие (через 3 с)") { testAction() })

        // События
        root.addView(header("События"))
        eventsView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        root.addView(eventsView)

        root.addView(header("Диагностика"))
        root.addView(button("Открыть разведчик") { startActivity(Intent(this, ScoutActivity::class.java)) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateModeRows() {
        if (!::thresholdRow.isInitialized) return
        val onRelease = Prefs.fireOnRelease(this)
        pressRow.visibility = if (onRelease) View.GONE else View.VISIBLE
        shortRow.visibility = if (onRelease) View.VISIBLE else View.GONE
        longRow.visibility = if (onRelease) View.VISIBLE else View.GONE
        thresholdRow.visibility = if (onRelease) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        if (!::shizukuStatus.isInitialized) return
        val st = ShizukuShell.state()
        shizukuStatus.text = (if (st == ShizukuShell.State.READY) "✅ " else "⚠️ ") + st.title
        shizukuButton.text = when (st) {
            ShizukuShell.State.READY -> "Shizuku готов"
            ShizukuShell.State.NO_PERMISSION -> "Дать разрешение"
            else -> "Как установить и запустить Shizuku"
        }
        shizukuButton.isEnabled = st != ShizukuShell.State.READY

        val addr = Prefs.address(this)
        deviceStatus.text = if (addr == null) "⚠️ Гимбал не выбран" else "✅ ${Prefs.deviceName(this) ?: "Гимбал"} · $addr"

        serviceStatus.text = (if (GimbalService.running) "▶ " else "■ ") + Bus.state
        serviceButton.text = if (GimbalService.running) "Остановить" else "Запустить"
        eventsView.text = if (Bus.events.isEmpty()) "—" else Bus.events.reversed().joinToString("\n")
    }

    // ------------------------------------------------------------------ Shizuku

    private fun onShizukuButton() {
        when (ShizukuShell.state()) {
            ShizukuShell.State.NO_PERMISSION -> ShizukuShell.requestPermission()
            ShizukuShell.State.READY -> {}
            else -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
        }
        handler.postDelayed({ refresh() }, 500)
    }

    private fun testAction() {
        val a = if (Prefs.fireOnRelease(this)) Prefs.shortAction(this) else Prefs.pressAction(this)
        Toast.makeText(this, "Через 3 секунды: ${a.title}. Переключитесь на камеру.", Toast.LENGTH_LONG).show()
        handler.postDelayed({
            ShizukuShell.runAction(a) { ok, msg -> Bus.event(if (ok) "Проверка → ${a.title}" else "Проверка ✗ ${a.title}: $msg") }
        }, 3000)
    }

    // ------------------------------------------------------------------ разрешения и сервис

    private fun neededPerms(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun btPermsOk(): Boolean {
        val bt = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return bt.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (!btPermsOk()) {
            Toast.makeText(this, "Без доступа к Bluetooth работать не получится", Toast.LENGTH_LONG).show()
            pendingStart = false
            return
        }
        if (pendingStart) {
            pendingStart = false
            startServiceChecked()
        } else {
            startScan()
        }
    }

    private fun toggleService() {
        if (GimbalService.running) {
            GimbalService.stop(this)
            handler.postDelayed({ refresh() }, 300)
            return
        }
        val missing = neededPerms()
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing, REQ_PERMS)
            return
        }
        startServiceChecked()
    }

    private fun startServiceChecked() {
        if (Prefs.address(this) == null) {
            Toast.makeText(this, "Сначала найдите гимбал", Toast.LENGTH_SHORT).show()
            return
        }
        if (ShizukuShell.state() != ShizukuShell.State.READY) {
            Toast.makeText(this, "Shizuku не готов — курок будет виден, но действия не сработают", Toast.LENGTH_LONG).show()
        }
        GimbalService.start(this)
        handler.postDelayed({ refresh() }, 300)
    }

    // ------------------------------------------------------------------ поиск гимбала

    private val stopScanRunnable = Runnable { stopScan() }
    private val found = LinkedHashMap<String, String>()

    private fun startScan() {
        if (!btPermsOk()) {
            requestPermissions(neededPerms(), REQ_PERMS)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (GimbalService.running) {
            Toast.makeText(this, "Сервис остановлен на время поиска", Toast.LENGTH_SHORT).show()
            GimbalService.stop(this)
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        stopScan()
        found.clear()
        deviceList.removeAllViews()
        deviceList.addView(label("Ищу… включите гимбал").apply { setPadding(0, dp(4), 0, dp(4)) })
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning = true
            handler.postDelayed(stopScanRunnable, 12000)
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка поиска: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        if (found.isEmpty() && ::deviceList.isInitialized) {
            deviceList.removeAllViews()
            deviceList.addView(label("Гимбал не найден. Он включён? Официальное приложение закрыто?"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val fallback: String? = try { result.device.name } catch (_: Exception) { null }
            val name = result.scanRecord?.deviceName ?: fallback ?: return
            if (!NAME_HINT.containsMatchIn(name)) return
            val addr = result.device.address
            handler.post {
                if (found.containsKey(addr)) return@post
                if (found.isEmpty()) deviceList.removeAllViews()
                found[addr] = name
                deviceList.addView(button("★ $name\n$addr") {
                    Prefs.setDevice(this@MainActivity, addr, name)
                    stopScan()
                    deviceList.removeAllViews()
                    Bus.event("Выбран гимбал $name")
                    refresh()
                })
            }
        }
    }
}
