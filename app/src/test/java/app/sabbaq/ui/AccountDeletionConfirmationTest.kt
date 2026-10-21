package app.sabbaq.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionConfirmationTest {
    @Test
    fun `exact word confirms, surrounding spaces and tatweel are tolerated`() {
        assertTrue(AccountDeletionConfirmation.matches("حذف"))
        assertTrue(AccountDeletionConfirmation.matches("  حذف "))
        assertTrue(AccountDeletionConfirmation.matches("حــذف"))
    }

    @Test
    fun `anything else keeps the destructive button disabled`() {
        assertFalse(AccountDeletionConfirmation.matches(""))
        assertFalse(AccountDeletionConfirmation.matches("حذ"))
        assertFalse(AccountDeletionConfirmation.matches("حذف الحساب"))
        assertFalse(AccountDeletionConfirmation.matches("DELETE"))
    }
}
