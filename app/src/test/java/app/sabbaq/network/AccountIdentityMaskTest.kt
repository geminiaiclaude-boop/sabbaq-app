package app.sabbaq.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountIdentityMaskTest {
    @Test
    fun `Saudi phone shows local prefix and last three digits only`() {
        assertEquals("05•••••678", AccountIdentityMask.phone("+966512345678"))
        assertEquals("05•••••678", AccountIdentityMask.phone("966512345678"))
        assertFalse(AccountIdentityMask.phone("+966512345678")!!.contains("12345"))
    }

    @Test
    fun `blank or short phone is not shown`() {
        assertNull(AccountIdentityMask.phone(null))
        assertNull(AccountIdentityMask.phone(""))
        assertNull(AccountIdentityMask.phone("12345"))
    }

    @Test
    fun `email keeps first character and domain`() {
        assertEquals("d•••@example.com", AccountIdentityMask.email(" driver@example.com "))
    }

    @Test
    fun `malformed email is not shown`() {
        assertNull(AccountIdentityMask.email("@example.com"))
        assertNull(AccountIdentityMask.email("driver@"))
        assertNull(AccountIdentityMask.email("a@b@c"))
        assertNull(AccountIdentityMask.email("   "))
    }
}
