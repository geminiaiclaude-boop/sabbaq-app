package app.sabbaq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sabbaq.Settings
import app.sabbaq.ui.theme.SabbaqTheme
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
        SabbaqTheme(darkTheme = false) {
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
        compose.onNodeWithText("تخطي الجولة").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test
    fun preset_auto_saves_the_filters() {
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
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.maxPickupKm == "3" }
        compose.runOnIdle {
            val saved = Settings.filters
            assertEquals(0, BigDecimal("3").compareTo(BigDecimal(saved.maxPickupKm)))
            assertEquals(0, BigDecimal("15").compareTo(BigDecimal(saved.maxTripKm)))
            assertTrue(saved.isPickupDistanceEnabled)
            assertTrue(saved.isPickupTimeEnabled)
            assertTrue(saved.isTripDistanceEnabled)
        }
    }

    @Test
    fun condition_switch_auto_saves_and_preserves_its_numeric_values_when_off() {
        show {
            SettingsScreen(
                notificationPermissionGranted = false,
                onRequestNotificationPermission = {},
                onOpenA11y = { Result.success(Unit) },
                onToggleKeeper = { Result.success(Unit) },
                initialDestination = DriverDestination.Filters,
            )
        }

        val fareSwitch = compose.onNodeWithTag("filter-switch-fare-range")
        compose.onNodeWithTag("filter-stepper-min-fare-increment").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.minFare == "1" }
        compose.runOnIdle { assertEquals(false, Settings.filters.isFareRangeEnabled) }

        fareSwitch.performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.isFareRangeEnabled }
        compose.runOnIdle {
            assertTrue(Settings.filters.isFareRangeEnabled)
            assertEquals("1", Settings.filters.minFare)
            assertEquals("100", Settings.filters.maxFare)
        }

        compose.onNodeWithTag("filter-switch-fare-range").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { !Settings.filters.isFareRangeEnabled }
        compose.runOnIdle {
            assertEquals(false, Settings.filters.isFareRangeEnabled)
            assertEquals("1", Settings.filters.minFare)
            assertEquals("100", Settings.filters.maxFare)
        }
    }

    @Test
    fun filters_show_horizontal_minimum_and_maximum_time_controls() {
        show {
            SettingsScreen(
                notificationPermissionGranted = false,
                onRequestNotificationPermission = {},
                onOpenA11y = { Result.success(Unit) },
                onToggleKeeper = { Result.success(Unit) },
                initialDestination = DriverDestination.Filters,
            )
        }

        compose.onNodeWithText("أقل وقت").performScrollTo().assertExists()
        compose.onNodeWithText("أقصى وقت").performScrollTo().assertExists()
        compose.onNodeWithText("قنص الطلبات بدون وجهة").performScrollTo().assertExists()
    }

    @Test
    fun no_destination_choice_is_retained_but_temporarily_disabled_by_exclusion_words() {
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
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.maxPickupKm == "3" }
        compose.onNodeWithTag("filter-switch-no-destination").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.acceptNoDestination }

        compose.onNodeWithText("الكلمات المستثناة").performScrollTo().performClick()
        compose.onNodeWithText("كلمات لا تناسبك").performScrollTo().performTextInput("airport")
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.excludeKeywords == listOf("airport") }
        compose.onNodeWithTag("filter-switch-no-destination").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(Settings.filters.acceptNoDestination)
            assertTrue(!Settings.filters.isAcceptNoDestinationEffective)
        }
        compose.onNodeWithText("كلمات لا تناسبك").performScrollTo().performTextClearance()
        compose.waitUntil(timeoutMillis = 10_000) { Settings.filters.excludeKeywords.isEmpty() }
        compose.onNodeWithTag("filter-switch-no-destination").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertTrue(Settings.filters.isAcceptNoDestinationEffective) }
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

        waitForText("لنجهّز سبّاق معًا")
        compose.onNodeWithText("قريب وسريع").performScrollTo().performClick()
        // The preset is saved automatically; the guide advances as soon as it is durable.
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
