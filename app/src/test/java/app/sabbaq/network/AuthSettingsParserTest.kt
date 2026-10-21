package app.sabbaq.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthSettingsParserTest {
    @Test
    fun `reads enabled providers from public auth settings`() {
        val body = """{"external":{"google":true,"phone":false,"email":true},"sms_provider":"twilio_verify"}"""
        assertEquals(AuthProviders(google = true, phone = false), AuthSettingsParser.parse(body))
    }

    @Test
    fun `missing provider flags count as disabled`() {
        assertEquals(AuthProviders(google = false, phone = false), AuthSettingsParser.parse("""{"external":{}}"""))
    }

    @Test
    fun `malformed or unexpected bodies are rejected so the UI keeps its default`() {
        assertNull(AuthSettingsParser.parse("not json"))
        assertNull(AuthSettingsParser.parse("""{"external":[]}"""))
        assertNull(AuthSettingsParser.parse("""{"settings":{}}"""))
    }
}
