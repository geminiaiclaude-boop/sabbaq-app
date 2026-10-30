package app.sabbaq.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The referral page reads exactly what `my_referrals` and `claim_referral_code` return. */
class ReferralParserTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun summary_with_referred_drivers() {
        val s = ReferralParser.summary(obj("""
            {"enabled":true,"my_code":"SB-7K9M2X4P","reward_days":7,"claim_window_days":7,"already_claimed":false,
             "can_claim":true,"days_earned":14,"invited":3,"rewarded":2,
             "referrals":[
               {"name":"Ali","phone":"+966512345678","claimed_at":"2026-10-20T10:00:00+00:00","status":"rewarded","reward_days":7,
                "subscription":"active","pro_until":"2026-11-20T10:00:00+00:00"},
               {"name":"Omar","phone":null,"claimed_at":"2026-10-25T10:00:00+00:00","status":"awaiting_first_payment","reward_days":0,
                "subscription":"awaiting_first_payment","pro_until":null},
               {"name":"Bad","phone":"call me","claimed_at":null,"status":"rejected_fraud","reward_days":0,"subscription":"expired","pro_until":null}]}
        """))!!
        assertEquals("SB-7K9M2X4P", s.myCode)
        assertTrue(s.canClaim)
        assertEquals(14, s.daysEarned)
        assertEquals(3, s.referrals.size)
        assertEquals("+966512345678", s.referrals[0].phone)
        assertEquals("active", s.referrals[0].subscription)
        assertNull("no phone, no WhatsApp", s.referrals[1].phone)
        assertNull("a malformed phone is dropped", s.referrals[2].phone)
        assertEquals("rejected_fraud", s.referrals[2].status)
    }

    @Test
    fun summary_without_a_code_is_rejected() {
        assertNull(ReferralParser.summary(obj("""{"enabled":true}""")))
    }

    @Test
    fun claim_answers() {
        assertTrue(ReferralParser.claim(obj("""{"ok":true,"status":"awaiting_first_payment","referrer_name":"Ali"}""")).ok)
        val refused = ReferralParser.claim(obj("""{"ok":false,"code":"same_device"}"""))
        assertFalse(refused.ok)
        assertEquals("same_device", refused.reason)
    }
}
