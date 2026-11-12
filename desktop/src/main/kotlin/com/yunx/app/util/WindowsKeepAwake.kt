package com.yunx.app.util

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载期间阻止系统睡眠：
 * - Windows：kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)。
 *   该状态是线程本地属性，所有调用固定投递到同一线程执行，引用计数归零时解除。
 * - macOS：拉起 `caffeinate -i -w <本进程 pid>`（本进程退出时 caffeinate 自动结束），引用计数归零时结束它。
 */
object WindowsKeepAwake {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int

        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        }
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    /** 所有调用固定在此线程（Windows 上状态是线程本地的，跨线程会失调） */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yunx-keep-awake") }
    private val refs = AtomicInteger(0)

    /** macOS：当前持有的 caffeinate 进程 */
    private var caffeinate: Process? = null

    fun acquire() {
        if (refs.getAndIncrement() == 0) {
            executor.execute {
                when {
                    OsPlatform.isMac -> runCatching {
                        if (caffeinate?.isAlive != true) {
                            caffeinate = ProcessBuilder(
                                "/usr/bin/caffeinate", "-i", "-w", ProcessHandle.current().pid().toString()
                            ).start()
                        }
                    }
                    OsPlatform.isWindows -> runCatching {
                        Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED)
                    }
                }
            }
        }
    }

    fun release() {
        if (refs.decrementAndGet() <= 0) {
            refs.set(0)
            executor.execute {
                when {
                    OsPlatform.isMac -> runCatching {
                        caffeinate?.destroy()
                        caffeinate = null
                    }
                    OsPlatform.isWindows -> runCatching {
                        Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS)
                    }
                }
            }
        }
    }
}
