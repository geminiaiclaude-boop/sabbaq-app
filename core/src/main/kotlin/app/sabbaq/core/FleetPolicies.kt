package app.sabbaq.core

import app.sabbaq.core.engine.EngineConfig

/**
 * Words the administration taught every app (published from the fleet's learning report). They
 * extend the reference engine's own extension point, the driver's extra keywords, and never
 * replace the built-in vocabulary.
 */
data class SharedLexicon(
    val version: Int = 0,
    val accept: List<String> = emptyList(),
    val time: List<String> = emptyList(),
    val currency: List<String> = emptyList(),
    val noise: List<String> = emptyList(),
) {
    val words: List<String> get() = accept + time + currency + noise

    /** [config] with these words appended to the driver's extra keywords (duplicates dropped). */
    fun applyTo(config: EngineConfig): EngineConfig {
        if (version <= 0) return config
        return config.copy(
            acceptKeywordsExtra = merge(config.acceptKeywordsExtra, accept),
            timeKeywordsExtra = merge(config.timeKeywordsExtra, time),
            currencyKeywordsExtra = merge(config.currencyKeywordsExtra, currency),
            noiseKeywordsExtra = merge(config.noiseKeywordsExtra, noise),
        )
    }

    private fun merge(own: String, shared: List<String>): String {
        val mine = own.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val seen = mine.map { it.lowercase() }.toHashSet()
        val added = shared.map { it.trim() }.filter { valid(it) && seen.add(it.lowercase()) }
        return (mine + added).joinToString(",")
    }

    companion object {
        /** Same rule as the server: 1–24 characters, no digits, commas or '@'. */
        fun valid(word: String): Boolean =
            word.length in 1..24 && word == word.trim() && word.none { it.isDigit() || it == ',' || it == '@' }
    }
}

/** What the app must do about its version. */
enum class UpdateState { NONE, AVAILABLE, REQUIRED }

/** The release the administration published (app_update_info). */
data class AppRelease(
    val latestCode: Long = 0,
    val latestName: String = "",
    val apkUrl: String = "",
    val apkSha256: String = "",
    val apkSize: Long = 0,
    val notesAr: String = "",
    val notesEn: String = "",
    val minCode: Long = 0,
) {
    fun state(installedCode: Long): UpdateState = when {
        minCode > installedCode -> UpdateState.REQUIRED
        latestCode > installedCode -> UpdateState.AVAILABLE
        else -> UpdateState.NONE
    }

    /** The direct build may download and install it itself; otherwise the driver is sent to the store. */
    val downloadable: Boolean
        get() = apkUrl.startsWith("https://") && apkUrl.endsWith(".apk") && SHA256.matches(apkSha256) && apkSize in 1..200_000_000L

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Why Android ended the process (ApplicationExitInfo reasons), as telemetry reason codes. The
 * report counts everything except the driver's own action, an app fault or an update as a
 * system kill.
 */
object ProcessExit {
    fun reason(code: Int): String = when (code) {
        1 -> "exit_self"
        2 -> "signaled"
        3 -> "low_memory"
        4, 5 -> "crash"
        6 -> "anr"
        7 -> "init_failure"
        8 -> "permission_change"
        9 -> "excessive_resource"
        10 -> "user_requested"
        11 -> "user_stopped"
        12 -> "dependency_died"
        13 -> "other"
        14 -> "freezer"
        15 -> "package_state"
        16 -> "package_updated"
        else -> "unknown"
    }

    private val NOT_SYSTEM = setOf("user_requested", "user_stopped", "crash", "anr", "exit_self", "package_updated", "package_state", "init_failure")

    fun isSystemKill(reason: String): Boolean = reason !in NOT_SYSTEM
}

/** App standby bucket as one letter for DEVICE_INFO: active, working set, frequent, rare, restricted. */
object StandbyBucket {
    fun letter(bucket: Int): String = when {
        bucket <= 10 -> "a"
        bucket <= 20 -> "w"
        bucket <= 30 -> "f"
        bucket <= 40 -> "r"
        else -> "x"
    }

    /** Rare and restricted buckets defer the app's work for hours. */
    fun atRisk(bucket: Int): Boolean = bucket > 30
}
