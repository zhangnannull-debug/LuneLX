package com.lunelx.music.lx.engine

import android.util.Log

/**
 * 极简日志门面，替代 OLX 里的 Timber。
 *
 * 与 Timber 的差异：只实现本项目实际用到的几种调用形态。
 * 带 `%s` 占位符的调用会走 [String.format]（仅当可变参非空时）。
 *
 * 统一 TAG，便于 logcat 过滤：`adb logcat -s LuneLX`
 */
internal object LxLog {
    private const val TAG = "LuneLX"

    private fun fmt(msg: String, args: Array<out Any?>): String =
        if (args.isEmpty()) msg else runCatching { String.format(msg, *args) }.getOrDefault(msg)

    fun d(msg: String, vararg args: Any?) = Log.d(TAG, fmt(msg, args))

    fun i(msg: String, vararg args: Any?) = Log.i(TAG, fmt(msg, args))

    fun w(msg: String, vararg args: Any?) = Log.w(TAG, fmt(msg, args))

    fun e(msg: String, vararg args: Any?) = Log.e(TAG, fmt(msg, args))

    /** 带异常。参数顺序是 (Throwable, String)，与 Timber 一致。 */
    fun w(t: Throwable, msg: String, vararg args: Any?) = Log.w(TAG, fmt(msg, args), t)

    fun e(t: Throwable, msg: String, vararg args: Any?) = Log.e(TAG, fmt(msg, args), t)
}
