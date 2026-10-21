package app.sabbaq.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseClientHardeningTest {
    @Test
    fun `عنوان الخدمة لا يقبل سوى HTTPS بلا بيانات اعتماد او استعلام`() {
        assertEquals(
            "https://project.supabase.co/",
            NetworkInputValidator.baseUrl(" https://project.supabase.co/ ")?.toString()
        )
        assertNull(NetworkInputValidator.baseUrl("http://project.supabase.co"))
        assertNull(NetworkInputValidator.baseUrl("https://user:pass@project.supabase.co"))
        assertNull(NetworkInputValidator.baseUrl("https://project.supabase.co?next=https://evil.example"))
    }

    @Test
    fun `الرموز تمنع التحكم وتطبيع الكود لا يعتمد على لغة الجهاز`() {
        assertNull(NetworkInputValidator.token("Bearer\nother"))
        assertNull(NetworkInputValidator.token(" token "))
        assertEquals("QAN-I", NetworkInputValidator.activationCode(" qan-i "))
        assertNull(NetworkInputValidator.activationCode("QAN\u0000BAD"))
    }

    @Test
    fun `معرف الجهاز يطابق حدود SQL البايتية من 16 الى 256 UTF-8`() {
        assertNull(NetworkInputValidator.deviceId("a".repeat(15)))
        assertEquals("a".repeat(16), NetworkInputValidator.deviceId("a".repeat(16)))
        assertEquals("a".repeat(256), NetworkInputValidator.deviceId("a".repeat(256)))
        assertNull(NetworkInputValidator.deviceId("a".repeat(257)))
        assertNull(NetworkInputValidator.deviceId("أ".repeat(129))) // 258 بايتًا في UTF-8
    }

    @Test
    fun `جلسة الدخول الأحدث فقط هي التي يمكنها التفعيل والربط`() {
        val store = AuthSessionStore()
        val firstAttempt = store.beginAuthentication()
        val secondAttempt = store.beginAuthentication()

        assertFalse(store.activate(firstAttempt, "first-token"))
        assertTrue(store.activate(secondAttempt, "second-token"))
        assertFalse(store.bindDevice("first-token", "device-a"))
        assertTrue(store.bindDevice("second-token", "device-b"))
        assertEquals("device-b", store.snapshot()?.deviceId)

        store.clearIfMatches("first-token")
        assertEquals("second-token", store.snapshot()?.token)
        store.clearIfMatches("second-token")
        assertNull(store.snapshot())
    }
}
