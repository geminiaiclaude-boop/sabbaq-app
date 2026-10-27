package app.sabbaq.admin

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminLogicTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun diagnose(funnel: String, events: String, has: Boolean = true): AdminDiagnosis =
        AdminDiagnostics.from(json.parseToJsonElement("""{"driver_has_telemetry":$has,"funnel":[$funnel],"events":[$events]}""") as JsonObject)

    private fun row(stage: String, count: Int, reason: String? = null) =
        """{"stage":"$stage","count":$count,"reason_code":${reason?.let { "\"$it\"" } ?: "null"}}"""

    private fun beat(reason: String, at: String = "2026-10-26T10:00:00Z") = """{"stage":"HEARTBEAT","reason_code":"$reason","occurred_at":"$at"}"""

    @Test
    fun `diagnosis names the cause and whether to guide the driver or ship a fix`() {
        assertEquals(AdminAction.USER, diagnose("", "", has = false).action)
        val disabled = diagnose(row("HEARTBEAT", 3, "a11y_disabled"), beat("a11y_disabled"))
        assertEquals(AdminAction.USER, disabled.action)
        assertTrue(disabled.cause.contains("إمكانية الوصول"))
        val unreadable = diagnose(listOf(row("HEARTBEAT", 1, "healthy"), row("PACKAGE_SEEN", 50), row("ROOT_UNAVAILABLE", 40, "active_other")).joinToString(","), beat("healthy"))
        assertEquals(AdminAction.UPDATE, unreadable.action)
        assertTrue(unreadable.cause.contains("غير قابلة للقراءة"))
        val rules = diagnose(listOf(row("HEARTBEAT", 1, "healthy"), row("PACKAGE_SEEN", 9), row("OFFER_DETECTED", 3), row("REJECTED", 3, "fare_too_low")).joinToString(","), beat("healthy"))
        assertEquals(AdminAction.USER, rules.action)
        assertTrue(rules.cause.contains("الحد الأدنى"))
        val noDestination = diagnose(listOf(row("HEARTBEAT", 1, "healthy"), row("PACKAGE_SEEN", 9), row("OFFER_DETECTED", 3),
            row("REJECTED", 1, "fare_too_low"), row("REJECTED", 5, "destination_missing")).joinToString(","), beat("healthy"))
        assertTrue(noDestination.advice.contains("بلا وجهة"))
        val keeper = diagnose(listOf(row("HEARTBEAT", 1, "healthy"), row("PAUSED", 1, "keeper_restore_timeout")).joinToString(","),
            """{"stage":"PAUSED","reason_code":"keeper_restore_timeout","occurred_at":"2026-10-26T11:00:00Z"},${beat("healthy")}""")
        assertEquals(AdminAction.USER, keeper.action)
        assertTrue(keeper.cause.contains("إعادة تشغيل"))
        val working = diagnose(listOf(row("HEARTBEAT", 1, "healthy"), row("PACKAGE_SEEN", 9), row("OFFER_DETECTED", 3), row("DECISION_ACCEPTED", 2),
            row("CLICK_RESULT", 2, "semantic"), row("CONFIRMATION_CONFIRMED", 2)).joinToString(","), beat("healthy"))
        assertEquals(AdminAction.NONE, working.action)
        assertEquals(1, working.events.size)
        assertEquals("نبضة", AdminDiagnostics.stageLabel("HEARTBEAT"))
    }

    @Test
    fun `profile publish refuses invalid content and a version that is not newer`() {
        val valid = """{"id":"jeeny","version":4,"packages":["me.com.easytaxista"],"acceptLabels":["قبول"],"currencyTokens":["sar"]}"""
        val published = listOf(AdminProfile("jeeny", 3, true, null, "{}"))
        assertNotNull(AdminRepository.validateProfile(valid, published).getOrNull())
        assertTrue(AdminRepository.validateProfile("{bad", published).exceptionOrNull()!!.message!!.contains("JSON"))
        assertTrue(AdminRepository.validateProfile(valid.replace("\"version\":4", "\"version\":3"), published).exceptionOrNull()!!.message!!.contains("ارفع رقم الإصدار"))
        assertTrue(AdminRepository.validateProfile(valid.replace("[\"قبول\"]", "[]"), published).exceptionOrNull()!!.message!!.contains("acceptLabels"))
        assertTrue(AdminRepository.validateProfile(valid.replace("\"jeeny\"", "\"Bad Id\""), emptyList()).exceptionOrNull()!!.message!!.contains("id"))
        assertTrue(AdminRepository.validateProfile(valid.replace("4,", "\"4\","), emptyList()).isFailure)
    }

    @Test
    fun `money is parsed into halalas without floating point and in any digit script`() {
        assertEquals(1875L, AdminInputs.sarToMinor("18.75"))
        assertEquals(1850L, AdminInputs.sarToMinor("١٨٫٥"))
        assertEquals(1800L, AdminInputs.sarToMinor("18"))
        assertNull(AdminInputs.sarToMinor("18.755"))
        assertNull(AdminInputs.sarToMinor("0"))
        assertNull(AdminInputs.sarToMinor("10000000"))
        assertEquals(100_000_000L, AdminInputs.sarToMinor("1000000"))
        assertEquals("18.75", AdminInputs.minorToSar(1875))
        assertEquals("0.05", AdminInputs.minorToSar(5))
        assertEquals(30, AdminInputs.boundedInt("٣٠", 1, 365))
        assertEquals(7, AdminInputs.boundedInt("007", 1, 365))
        assertNull(AdminInputs.boundedInt("366", 1, 365))
        assertNull(AdminInputs.boundedInt("1.5", 1, 365))
    }

    @Test
    fun `whatsapp numbers become wa_me digits`() {
        assertEquals(AdminInputs.Whatsapp.Valid("966512345678"), AdminInputs.whatsapp("0512345678"))
        assertEquals(AdminInputs.Whatsapp.Valid("966512345678"), AdminInputs.whatsapp("+966 51 234 5678"))
        assertEquals(AdminInputs.Whatsapp.Valid("966512345678"), AdminInputs.whatsapp("00966512345678"))
        assertEquals(AdminInputs.Whatsapp.Empty, AdminInputs.whatsapp("  "))
        assertEquals(AdminInputs.Whatsapp.Invalid, AdminInputs.whatsapp("12ab"))
    }

    @Test
    fun `csv neutralises formulas and quotes every field`() {
        assertEquals("\"'=1+1\"", AdminInputs.csvField("=1+1"))
        assertEquals("\"' \t@SUM(A1:A2)\"", AdminInputs.csvField(" \t@SUM(A1:A2)"))
        assertEquals("\"a\"\"b\"", AdminInputs.csvField("a\"b"))
        val csv = AdminInputs.codesCsv(listOf(AdminExportCode("ABC", 30, null)))
        assertTrue(csv.startsWith("\uFEFF\"الكود\""))
        assertTrue(csv.endsWith("\r\n\"ABC\",\"30\",\"\""))
    }

    @Test
    fun `contact links are https without spaces`() {
        assertTrue(AdminInputs.isHttpsContact("https://wa.me/966512345678"))
        assertTrue(!AdminInputs.isHttpsContact("http://example.com"))
        assertTrue(!AdminInputs.isHttpsContact("https://a b"))
        assertTrue(!AdminInputs.isHttpsContact("https://" + "a".repeat(2050)))
    }

    @Test
    fun `a policy reply missing any confirmed field never fills the form`() {
        val full = """{"trial_enabled":true,"trial_days":3,"price_minor":1875,"currency":"SAR","subscription_days":30,
            "purchase_contact_url":null,"admin_whatsapp":"966512345678","updated_at":"2026-10-27T10:00:00+00:00"}"""
        val policy = AdminRepository.parsePolicy(json.parseToJsonElement(full) as JsonObject)!!
        assertEquals(1875L, policy.priceMinor)
        assertEquals("966512345678", policy.whatsapp)
        assertEquals(Instant.parse("2026-10-27T10:00:00Z"), policy.updatedAt)
        assertNull(AdminRepository.parsePolicy(json.parseToJsonElement(full.replace("\"SAR\"", "\"USD\"")) as JsonObject))
        assertNull(AdminRepository.parsePolicy(json.parseToJsonElement(full.replace("\"trial_days\":3", "\"trial_days\":0")) as JsonObject))
        assertNull(AdminRepository.parsePolicy(json.parseToJsonElement(full.replace("null", "\"http://x\"")) as JsonObject))
    }

    @Test
    fun `driver version reads like the web panel`() {
        val at = { _: Instant -> "الآن" }
        assertEquals("2.1.0 (9) · أندرويد 14", AdminInputs.describeVersion(AdminDriverVersion("2.1.0", 9, 34, null), at))
        assertEquals("رمز 7", AdminInputs.describeVersion(AdminDriverVersion(null, 7, null, null), at))
        assertEquals("غير معروف", AdminInputs.describeVersion(null, at))
        assertEquals("لم يتصل بعد · آخر ظهور الآن", AdminInputs.describeVersion(AdminDriverVersion(null, null, null, Instant.EPOCH), at))
    }

    @Test
    fun `code generation accepts only what the server accepts`() {
        assertEquals(Triple(10, 30, null), AdminRepository.generationValues("10", 30, "  "))
        assertEquals(Triple(5, 7, "دفعة"), AdminRepository.generationValues("٥", 7, " دفعة "))
        assertNull(AdminRepository.generationValues("0", 30, ""))
        assertNull(AdminRepository.generationValues("501", 30, ""))
        assertNull(AdminRepository.generationValues("10", 31, ""))
        assertNull(AdminRepository.generationValues("10", 30, "a\u0001"))
    }
}
