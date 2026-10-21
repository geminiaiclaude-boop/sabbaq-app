package app.qannas

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * On-device crash record for support diagnosis, with no third-party SDK and nothing sent
 * anywhere unless the driver chooses to share it. Only exception types and stack frames
 * are kept: messages are dropped because they can carry user input or server responses.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_FRAMES = 40

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(appContext).writeText(format(error, Instant.now(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context): Boolean = runCatching { file(context).delete() || !file(context).exists() }.getOrDefault(false)

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** Pure and message-free so it can be unit tested and safely shared. */
    internal fun format(error: Throwable, at: Instant, versionName: String, versionCode: Int): String = buildString {
        appendLine("Qannas $versionName ($versionCode)")
        appendLine("time: $at")
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            appendLine((if (depth == 0) "" else "caused by: ") + current.javaClass.name)
            current.stackTrace.take(MAX_FRAMES).forEach { appendLine("  at $it") }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }
}
