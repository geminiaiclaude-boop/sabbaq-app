package app.sabbaq.core

/**
 * Recognises supported ride apps among the launchable apps on the phone, whatever package they
 * carry: the profiles' packages first, then the app's name, so a regional build, a renamed
 * package or a copy in another profile is found and reported instead of silently missing every
 * offer. Passenger apps of the same brands are excluded.
 */
object RideAppDiscovery {
    data class Candidate(val provider: String, val packageName: String, val known: Boolean)

    private val PASSENGER_PACKAGES = setOf("me.com.easytaxi", "ee.mtakso.client", "com.petraride", "com.petraride.passenger")

    private val NAME_HINTS = mapOf(
        "jeeny" to Regex("jeeny|جيني|easy\\s?taxi|ايزي\\s?تاكسي|easytaxista", RegexOption.IGNORE_CASE),
        "bolt" to Regex("bolt\\s?driver|سائق\\s?بولت|بولت\\s?للسائق|mtakso\\.driver", RegexOption.IGNORE_CASE),
        "petra" to Regex("petra\\s?ride\\s?captain|petraride_captain|بترا", RegexOption.IGNORE_CASE),
    )

    private val UNSAFE_DETAIL = Regex("address|fare|phone|email|token|http")

    fun classify(packageName: String, label: String?, profiles: List<RideProfile>): Candidate? {
        if (packageName.isBlank()) return null
        profiles.firstOrNull { packageName in it.packages }?.let { return Candidate(it.id, packageName, known = true) }
        if (packageName.lowercase() in PASSENGER_PACKAGES) return null
        val haystack = "$packageName ${label.orEmpty()}"
        return NAME_HINTS.entries.firstOrNull { it.value.containsMatchIn(haystack) }?.let { Candidate(it.key, packageName, known = false) }
    }

    /** A package name inside the telemetry detail contract, or null when it cannot be sent safely. */
    fun detailFor(packageName: String): String? =
        packageName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }
            .take(OfferTelemetryContract.MAX_DETAIL_LENGTH)
            .takeIf { it.isNotEmpty() && !UNSAFE_DETAIL.containsMatchIn(it) }
}
