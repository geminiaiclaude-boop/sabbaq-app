package app.sabbaq.network

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One driver who used this driver's invite code (`my_referrals`). */
data class ReferredDriver(
    val name: String,
    /** Verified phone, or null (Google-only accounts): no WhatsApp button then. */
    val phone: String?,
    val claimedAt: Instant?,
    /** awaiting_first_payment | rewarded | rejected_fraud */
    val status: String,
    val rewardDays: Int,
    /** awaiting_first_payment | active | expired */
    val subscription: String,
    val proUntil: Instant?,
)

/** The referral page: own code, whether a code may still be entered, totals and the list. */
data class ReferralSummary(
    val enabled: Boolean,
    val myCode: String,
    val rewardDays: Int,
    val claimWindowDays: Int,
    val alreadyClaimed: Boolean,
    val canClaim: Boolean,
    val daysEarned: Int,
    val invited: Int,
    val rewarded: Int,
    val referrals: List<ReferredDriver>,
)

/** `claim_referral_code`: "ok", or the server's reason (invalid_code, same_device, …). */
data class ReferralClaim(val ok: Boolean, val reason: String?, val referrerName: String?)

internal object ReferralParser {
    fun summary(row: JsonObject): ReferralSummary? {
        val code = row.str("my_code") ?: return null
        return ReferralSummary(
            enabled = row.bool("enabled") ?: true,
            myCode = code,
            rewardDays = row.int("reward_days") ?: 7,
            claimWindowDays = row.int("claim_window_days") ?: 7,
            alreadyClaimed = row.bool("already_claimed") ?: false,
            canClaim = row.bool("can_claim") ?: false,
            daysEarned = row.int("days_earned") ?: 0,
            invited = row.int("invited") ?: 0,
            rewarded = row.int("rewarded") ?: 0,
            referrals = (row["referrals"] as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                ReferredDriver(
                    name = o.str("name").orEmpty(),
                    phone = o.str("phone")?.takeIf { it.matches(Regex("^\\+?[0-9]{7,15}$")) },
                    claimedAt = o.str("claimed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                    status = o.str("status") ?: "awaiting_first_payment",
                    rewardDays = o.int("reward_days") ?: 0,
                    subscription = o.str("subscription") ?: "awaiting_first_payment",
                    proUntil = o.str("pro_until")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() },
                )
            },
        )
    }

    fun claim(row: JsonObject): ReferralClaim =
        ReferralClaim(row.bool("ok") ?: false, row.str("code"), row.str("referrer_name"))

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
}
