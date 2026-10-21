package app.sabbaq.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProfileBundle(val profiles: List<RideProfile>)

object Profiles {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Invalid remote entries are ignored so they cannot disable safe bundled parsing. */
    fun decode(text: String): List<RideProfile> = newestUsable(json.decodeFromString(ProfileBundle.serializer(), text).profiles)

    /** Profiles shipped inside the build; used until the server sends newer ones. */
    fun bundled(): List<RideProfile> {
        val stream = Profiles::class.java.getResourceAsStream("/default_profiles.json")
            ?: error("default_profiles.json missing from the build")
        return stream.bufferedReader(Charsets.UTF_8).use { decode(it.readText()) }
    }

    /** Server profiles win only when strictly newer, per profile id. */
    fun merge(local: List<RideProfile>, remote: List<RideProfile>): List<RideProfile> {
        val byId = LinkedHashMap<String, RideProfile>()
        newestUsable(local).forEach { profile ->
            val current = byId[profile.id]
            if (current == null || profile.version > current.version) byId[profile.id] = profile
        }
        newestUsable(remote).forEach { r ->
            val current = byId[r.id]
            if (current == null || r.version > current.version) byId[r.id] = r
        }
        return byId.values.toList()
    }

    fun isUsable(profile: RideProfile): Boolean =
        profile.id.isNotBlank() && profile.id == profile.id.trim() &&
            Text.normalize(profile.name).isNotEmpty() &&
            profile.version > 0 &&
            profile.packages.isNotEmpty() && profile.packages.all { it.isNotBlank() && it == it.trim() } &&
            (profile.acceptLabels.any { Text.normalize(it).isNotEmpty() } ||
                profile.acceptViewIds.any { it.isNotBlank() && it == it.trim() }) &&
            profile.currencyTokens.any { Text.normalize(it).isNotEmpty() }

    private fun newestUsable(profiles: List<RideProfile>): List<RideProfile> {
        val byId = LinkedHashMap<String, RideProfile>()
        profiles.forEach { profile ->
            if (!isUsable(profile)) return@forEach
            val current = byId[profile.id]
            if (current == null || profile.version > current.version) byId[profile.id] = profile
        }
        return byId.values.toList()
    }
}
