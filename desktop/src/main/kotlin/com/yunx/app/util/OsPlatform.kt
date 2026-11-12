package com.yunx.app.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行平台判断与少量跨平台小工具（macOS 移植引入）。
 *
 * 原有代码里散落着 `os.name.contains("win")` 判断，新代码统一走这里；
 * 旧判断保留不动，避免无谓改动 Windows 行为。
 */
object OsPlatform {

    private val osName: String = System.getProperty("os.name", "").lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")
    val isLinux: Boolean = !isWindows && !isMac

    /** CPU 架构是否为 ARM64（Apple Silicon / Windows on ARM） */
    val isArm64: Boolean = System.getProperty("os.arch", "").lowercase().let { it == "aarch64" || it == "arm64" }

    /** 可执行文件后缀：Windows 为 `.exe`，其余为空 */
    val exeSuffix: String get() = if (isWindows) ".exe" else ""

    /** 给可执行文件基础名补平台后缀：`exe("ffplay")` → Windows `ffplay.exe` / macOS `ffplay` */
    fun exe(baseName: String): String = baseName + exeSuffix

    /**
     * 同步执行一条命令并取回标准输出（去首尾空白）；失败 / 超时 / 非零退出返回 null。
     * 只用于读取系统信息之类的短命令，不要拿来跑长任务。
     */
    fun runForOutput(vararg command: String, timeoutSec: Long = 5): String? = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(false).start()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        if (p.exitValue() != 0) null else out.trim()
    }.getOrNull()

    /** 启动命令但不等待（打开文件 / 显示 Finder 等） */
    fun launch(vararg command: String): Boolean = runCatching {
        ProcessBuilder(*command).start()
        true
    }.getOrDefault(false)

    /**
     * 让下载下来的可执行文件能跑起来（macOS / Linux）：补可执行位，
     * macOS 上再去掉 Gatekeeper 隔离属性（网络下载的文件会带 com.apple.quarantine）。
     */
    fun makeExecutable(file: File) {
        if (isWindows || !file.isFile) return
        runCatching { file.setExecutable(true, false) }
        if (isMac) runForOutput("/usr/bin/xattr", "-d", "com.apple.quarantine", file.absolutePath)
    }

    /** macOS：系统外观是否为深色（`defaults read -g AppleInterfaceStyle` 输出 Dark） */
    fun macIsDarkMode(): Boolean =
        isMac && runForOutput("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle")
            ?.equals("Dark", ignoreCase = true) == true

    /**
     * macOS：当前进程所在的 `.app` 包（jpackage 打包后 `jpackage.app-path` 指向
     * `YunX-Desktop.app/Contents/MacOS/YunX-Desktop`）；开发运行返回 null。
     */
    fun macAppBundle(): File? {
        if (!isMac) return null
        val launcher = System.getProperty("jpackage.app-path")?.let { File(it) } ?: return null
        var dir: File? = launcher.absoluteFile
        while (dir != null) {
            if (dir.name.endsWith(".app")) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** macOS 下通过 AppleScript 弹系统通知（无需额外权限；失败静默） */
    fun macNotify(title: String, message: String) {
        if (!isMac) return
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        launch(
            "/usr/bin/osascript", "-e",
            "display notification \"${esc(message)}\" with title \"${esc(title)}\""
        )
    }
}
