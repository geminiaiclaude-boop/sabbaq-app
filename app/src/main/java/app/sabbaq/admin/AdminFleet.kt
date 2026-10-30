package app.sabbaq.admin

import app.sabbaq.core.SharedLexicon
import app.sabbaq.network.SupabaseClient.FeatureException
import app.sabbaq.network.SupabaseClient.FeatureFailure
import app.sabbaq.network.FleetParser
import app.sabbaq.network.SupabaseClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** The administration side of 0016: version report, learning report and lexicon, release publishing. */
object AdminFleet {
    private val json = Json { ignoreUnknownKeys = true }

    data class VersionRow(
        val version: String, val devices: Long, val drivers: Long, val accepted: Long, val confirmed: Long,
        val unconfirmed: Long, val readMisses: Long, val rejected: Long, val crashes: Long, val systemKills: Long, val tapP50Ms: Long?,
    )
    data class MakerRow(val maker: String, val devices: Long, val atRisk: Long, val systemKills: Long)
    data class ExitRow(val version: String, val reason: String, val count: Long)
    data class VersionReport(val versions: List<VersionRow>, val makers: List<MakerRow>, val exits: List<ExitRow>)

    data class Candidate(val word: String, val onButton: Boolean, val count: Long, val drivers: Long)
    data class Signature(val provider: String, val targetVersion: String, val detail: String, val count: Long)
    data class Miss(val provider: String, val targetVersion: String, val stage: String, val reason: String, val count: Long)
    data class LearningReport(
        val lexicon: SharedLexicon, val candidates: List<Candidate>, val signatures: List<Signature>,
        val misses: List<Miss>, val newSamples: Long,
    )

    data class Proposal(
        val id: Long, val category: String, val word: String, val status: String,
        val drivers: Long, val samples: Long, val shadowHits: Long, val shadowDrivers: Long,
    )

    suspend fun proposals(): Result<List<Proposal>> = SupabaseClient.adminRpc("admin_lexicon_proposals").mapCatching { body ->
        (json.parseToJsonElement(body) as? JsonArray ?: throw FeatureException(FeatureFailure.UNAVAILABLE)).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Proposal(o.l("id"), o.s("category"), o.s("word"), o.s("status"), o.l("drivers"), o.l("samples"), o.l("shadow_hits"), o.l("shadow_drivers"))
        }
    }

    /** approve / reject / revert one proposal. */
    suspend fun decide(id: Long, decision: String): Result<Unit> {
        if (decision !in setOf("approve", "reject", "revert")) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return SupabaseClient.adminRpc("admin_decide_proposal", buildJsonObject { put("p_id", id); put("p_decision", decision) }).map { }
    }

    suspend fun runLearning(): Result<Unit> = SupabaseClient.adminRpc("admin_run_learning").map { }

    data class Adoption(val version: String, val drivers: Long)
    data class Release(val release: app.sabbaq.core.AppRelease, val adoption: List<Adoption>)

    suspend fun versionReport(): Result<VersionReport> = SupabaseClient.adminRpc("admin_version_report").mapCatching { body ->
        val o = obj(body)
        VersionReport(
            versions = o.rows("versions").map {
                VersionRow(it.s("app_version"), it.l("devices"), it.l("drivers"), it.l("accepted"), it.l("confirmed"),
                    it.l("unconfirmed"), it.l("read_misses"), it.l("rejected_by_conditions"), it.l("crashes"), it.l("system_kills"),
                    it.lOrNull("tap_p50_ms"))
            },
            makers = o.rows("manufacturers").map { MakerRow(it.s("manufacturer").ifEmpty { "?" }, it.l("devices"), it.l("at_risk"), it.l("system_kills")) },
            exits = o.rows("exit_reasons").map { ExitRow(it.s("app_version"), it.s("reason_code"), it.l("count")) },
        )
    }

    suspend fun learningReport(): Result<LearningReport> = SupabaseClient.adminRpc("admin_learning_report").mapCatching { body ->
        val o = obj(body)
        LearningReport(
            lexicon = (o["lexicon"] as? JsonObject)?.let(FleetParser::lexicon) ?: SharedLexicon(),
            candidates = o.rows("candidate_words").map {
                Candidate(it.s("word"), (it["on_button"] as? JsonPrimitive)?.contentOrNull == "true", it.l("count"), it.l("drivers"))
            },
            signatures = o.rows("accept_signatures").map { Signature(it.s("provider"), it.s("target_app_version"), it.s("detail"), it.l("count")) },
            misses = o.rows("misses").map { Miss(it.s("provider"), it.s("target_app_version"), it.s("stage"), it.s("reason_code"), it.l("count")) },
            newSamples = ((o["samples"] as? JsonObject)?.l("new")) ?: 0,
        )
    }

    /** Publishes the lexicon; every list is validated here and again on the server. */
    suspend fun publishLexicon(accept: List<String>, time: List<String>, currency: List<String>, noise: List<String>): Result<SharedLexicon> {
        val lists = listOf(accept, time, currency, noise)
        if (lists.any { l -> l.size > 30 || l.any { !SharedLexicon.valid(it) } }) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return SupabaseClient.adminRpc("admin_set_shared_lexicon", buildJsonObject {
            putJsonArray("p_accept") { accept.forEach { add(it) } }
            putJsonArray("p_time") { time.forEach { add(it) } }
            putJsonArray("p_currency") { currency.forEach { add(it) } }
            putJsonArray("p_noise") { noise.forEach { add(it) } }
        }).mapCatching { FleetParser.lexicon(obj(it)) ?: throw FeatureException(FeatureFailure.UNAVAILABLE) }
    }

    suspend fun release(): Result<Release> = SupabaseClient.adminRpc("admin_app_update").mapCatching { body ->
        val o = obj(body)
        Release(FleetParser.release(o) ?: throw FeatureException(FeatureFailure.UNAVAILABLE),
            o.rows("adoption").map { Adoption(it.s("app_version"), it.l("drivers")) })
    }

    suspend fun publishRelease(
        code: Long, name: String, url: String, sha256: String, size: Long, notesAr: String, notesEn: String, minCode: Long?,
    ): Result<app.sabbaq.core.AppRelease> {
        val candidate = app.sabbaq.core.AppRelease(code, name, url, sha256.lowercase(), size, notesAr, notesEn, minCode ?: 0)
        if (code <= 0 || !Regex("^[A-Za-z0-9._-]{1,32}$").matches(name) || !candidate.downloadable ||
            notesAr.length > 1000 || notesEn.length > 1000 || (minCode != null && minCode !in 1..code)) {
            return Result.failure(FeatureException(FeatureFailure.REJECTED))
        }
        return SupabaseClient.adminRpc("admin_publish_app_update", buildJsonObject {
            put("p_latest_code", code); put("p_latest_name", name); put("p_apk_url", url); put("p_apk_sha256", sha256.lowercase())
            put("p_apk_size", size); put("p_notes_ar", notesAr); put("p_notes_en", notesEn)
            if (minCode != null) put("p_min_code", minCode)
        }).mapCatching { FleetParser.release(obj(it)) ?: throw FeatureException(FeatureFailure.UNAVAILABLE) }
    }

    private fun obj(body: String): JsonObject = json.parseToJsonElement(body) as? JsonObject ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
    private fun JsonObject.rows(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.lOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    private fun JsonObject.l(key: String): Long = lOrNull(key) ?: 0
}
