package app.qannas.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.qannas.Settings
import app.qannas.ui.theme.QannasTheme
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI flows on a real Android runtime, without accounts, network
 * credentials or the accessibility service. Each test drives the real composables.
 */
@RunWith(AndroidJUnit4::class)
class DriverFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun resetLocalState() {
        Settings.init(InstrumentationRegistry.getInstrumentation().targetContext)
        Settings.clearAccountData()
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        QannasTheme(darkTheme = false) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() }
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.onAllNodesWithTextCount(text) > 0
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    fun onboarding_moves_forward_back_and_skips() {
        var continued = false
        show { OnboardingScreen(onContinue = { continued = true }) }

        compose.onNodeWithText("كيف يعمل؟").performScrollTo().performClick()
        waitForText("تفاصيل المثال")
        compose.onNodeWithText("رجوع").performScrollTo().performClick()
        waitForText("طلبات على مقاس شروطك")
        compose.onNodeWithText("تخطي الجولة").performClick()
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test
    fun preset_then_save_persists_the_filters() {
        show {
            SettingsScreen(
                notificationPermissionGranted = false,
                onRequestNotificationPermission = {},
                onOpenA11y = { Result.success(Unit) },
                onToggleKeeper = { Result.success(Unit) },
                initialDestination = DriverDestination.Filters,
            )
        }

        compose.onNodeWithText("قريب وسريع").performScrollTo().performClick()
        compose.onNodeWithText("حفظ شروط الطلبات").performScrollTo().assertIsEnabled().performClick()
        waitForText("حُفظت شروطك. ستُستخدم عند بدء متابعة الطلبات.")
        compose.runOnIdle {
            val saved = Settings.filters
            assertEquals(0, BigDecimal("3").compareTo(BigDecimal(saved.maxPickupKm)))
            assertEquals(0, BigDecimal("15").compareTo(BigDecimal(saved.maxTripKm)))
        }
    }

    @Test
    fun simulator_explains_why_an_offer_does_not_match() {
        show {
            SettingsScreen(
                notificationPermissionGranted = false,
                onRequestNotificationPermission = {},
                onOpenA11y = { Result.success(Unit) },
                onToggleKeeper = { Result.success(Unit) },
                initialDestination = DriverDestination.Simulator,
            )
        }

        compose.onNodeWithText("اعرف هل يناسبك").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("سعر الطلب").performScrollTo().performTextInput("30")
        compose.onNodeWithText("اعرف هل يناسبك").performScrollTo().assertIsEnabled().performClick()
        waitForText("نتيجة التجربة: لا يناسبك")
        waitForText("• لم تحدد شرطًا لاختيار الطلبات.")
    }

    @Test
    fun setup_guide_advances_when_conditions_are_saved() {
        show {
            SettingsScreen(
                notificationPermissionGranted = false,
                onRequestNotificationPermission = {},
                onOpenA11y = { Result.success(Unit) },
                onToggleKeeper = { Result.success(Unit) },
                initialDestination = DriverDestination.Setup,
            )
        }

        waitForText("لنجهّز قناص معًا")
        compose.onNodeWithText("قريب وسريع").performScrollTo().performClick()
        compose.onNodeWithText("حفظ الشروط").performScrollTo().performClick()
        // Step 1 is done, so the guide moves on to the permission step by itself.
        waitForText("تفعيل الإذن")
        compose.runOnIdle { assertTrue(Settings.filters.anyActive()) }
    }

    @Test
    fun sign_in_choice_opens_its_own_sign_in_page() {
        show { LoginScreen(onLoginSuccess = {}) }

        // Page 1 is only the choice; choosing opens the sign-in page shaped by it.
        waitForText("كيف تريد أن تبدأ؟")
        compose.onNodeWithText("اشترك الآن").performScrollTo().performClick()
        waitForText("سجّل الدخول أو أنشئ حسابك")
        compose.onNodeWithText("تغيير اختيارك").performScrollTo().performClick()
        waitForText("كيف تريد أن تبدأ؟")
        compose.onNodeWithText("لدي اشتراك").performScrollTo().performClick()
        waitForText("تسجيل الدخول")
    }

    @Test
    fun account_deletion_needs_typed_confirmation_and_fails_safely_without_session() {
        show {
            SubscriptionScreen(
                licenseInMemory = false,
                externalMessage = null,
                externalMessageIsError = false,
                onLicenseChanged = {},
                onMessage = { _, _ -> },
            )
        }

        compose.onNodeWithText("حذف حسابي").performScrollTo().performClick()
        waitForText("حذف الحساب نهائيًا؟")
        compose.onNodeWithText("حذف نهائي").assertIsNotEnabled()
        compose.onNodeWithText("اكتب «حذف» للتأكيد").performTextInput("حذف")
        compose.onNodeWithText("حذف نهائي").assertIsEnabled().performClick()
        // No session exists in this test: the request must be refused locally with a clear message.
        waitForText("انتهت الجلسة؛ سجّل الدخول من جديد")
    }
}
