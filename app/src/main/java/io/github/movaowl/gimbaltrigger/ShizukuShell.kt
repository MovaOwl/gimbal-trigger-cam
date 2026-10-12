package io.github.movaowl.gimbaltrigger

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Выполнение команд с правами ADB через Shizuku. */
object ShizukuShell {
    enum class State(val title: String) {
        STOPPED("Shizuku не запущен (или не установлен)"),
        TOO_OLD("Shizuku слишком старый — обновите"),
        NO_PERMISSION("Нужно разрешение для приложения"),
        READY("Готов"),
    }

    const val REQUEST_CODE = 42

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.STOPPED
            Shizuku.isPreV11() -> State.TOO_OLD
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NO_PERMISSION
        }
    } catch (_: Throwable) {
        State.STOPPED
    }

    fun requestPermission() {
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) {}
    }

    // В API 13 Shizuku.newProcess закрыт, но стабильно доступен через reflection.
    private val newProcess: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /** Выполняет команду в фоне; результат (успех, текст) — на главном потоке. */
    fun run(cmd: String, onDone: (Boolean, String) -> Unit) {
        executor.execute {
            val result = try {
                if (state() != State.READY) {
                    false to state().title
                } else {
                    val p = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                    val finished = p.waitFor(5, TimeUnit.SECONDS)
                    if (!finished) {
                        p.destroy()
                        false to "команда не завершилась за 5 с"
                    } else {
                        val err = p.errorStream.bufferedReader().readText().trim()
                        val code = p.exitValue()
                        (code == 0) to (if (code == 0) "OK" else "код $code ${err.take(200)}")
                    }
                }
            } catch (e: Throwable) {
                false to (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
            }
            main.post { onDone(result.first, result.second) }
        }
    }

    fun runAction(a: GAction, onDone: (Boolean, String) -> Unit) {
        val cmd = a.shell ?: return onDone(true, "ничего")
        run(cmd, onDone)
    }
}
