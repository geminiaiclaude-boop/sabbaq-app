package app.qannas.core

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

    fun decode(text: String): List<RideProfile> = json.decodeFromString(ProfileBundle.serializer(), text).profiles

    /** Profiles shipped inside the build; used until the server sends newer ones. */
    fun bundled(): List<RideProfile> {
        val stream = Profiles::class.java.getResourceAsStream("/default_profiles.json")
            ?: error("default_profiles.json missing from the build")
        return stream.bufferedReader(Charsets.UTF_8).use { decode(it.readText()) }
    }

    /** Server profiles win only when strictly newer, per profile id. */
    fun merge(local: List<RideProfile>, remote: List<RideProfile>): List<RideProfile> {
        val byId = LinkedHashMap<String, RideProfile>()
        local.forEach { byId[it.id] = it }
        remote.forEach { r ->
            val current = byId[r.id]
            if (current == null || r.version > current.version) byId[r.id] = r
        }
        return byId.values.toList()
    }
}
