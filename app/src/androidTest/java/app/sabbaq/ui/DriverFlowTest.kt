package app.sabbaq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sabbaq.Settings
import app.sabbaq.ThemeMode
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
        // These flows assert the Arabic UI. Compose dialog windows re-provide the activity's own
        // context and ignore the override in show(), so the runner (scripts/android_flow_tests.py)
        // starts the app process with the Arabic per-app locale; the default locale is pinned here
        // too so the locale-aware helpers (LocaleManager.isArabic) agree with the resources.
        java.util.Locale.setDefault(java.util.Locale("ar"))
        Settings.init(InstrumentationRegistry.getInstrumentation().targetContext)
        Settings.clearAccountData()
    }

    // The UI strings now come from resources; these flows are authored against the Arabic text,
    // so the content is composed with an Arabic context regardless of the emulator's language.
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val arabic = remember {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale("ar"))
            base.createConfigurationContext(config)
        }
        CompositionLocalProvider(
            LocalContext provides arabic,
            LocalConfiguration provides arabic.resources.configuration,
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            SabbaqTheme(darkTheme = false) { content() }
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

        compose.onNodeWithText("التالي").performClick()
        waitForText("أنت تحدد، وسبّاق يتابع")
        compose.onNodeWithText("كيف يعمل؟").performClick()
        waitForText("تفاصيل المثال")
        compose.onNodeWithText("رجوع").performClick()
        waitForText("أنت تحدد، وسبّاق يتابع")
        compose.onNodeWithText("رجوع").performClick()
        waitForText("طلبات على مقاس شروطك")
        compose.onNodeWithText("تخطي الجولة").performClick()
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test
    fun authenticated_shell_drawer_exposes_driver_pages_without_removing_top_bar_actions() {
        var selected: DriverDestination? = null
        show {
            SabbaqShell(
                showHeader = true,
                themeMode = ThemeMode.SYSTEM,
                onThemeChange = {},
                onLogout = {},
                isAuthenticated = true,
                activeDriverDestination = DriverDestination.Dashboard,
                onOpenDriverDestination = { selected = it },
            ) {}
        }

        compose.onNodeWithText("طلبات تناسبك").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("فتح القائمة الجانبية").performClick()
        waitForText("طلبات تناسبك")
        compose.onNodeWithText("طلبات تناسبك").performClick()
        compose.runOnIdle { assertEquals(DriverDestination.Filters, selected) }
    }

    @Test
    fun unauthenticated_shell_drawer_keeps_login_and_legal_paths_available() {
        var loginRequested = false
        show {
            SabbaqShell(
                showHeader = true,
                themeMode = ThemeMode.SYSTEM,
                onThemeChange = {},
                onLogout = {},
                onOpenLogin = { loginRequested = true },
            ) {}
        }

        compose.onNodeWithContentDescription("فتح القائمة الجانبية").performClick()
        waitForText("تسجيل الدخول")
        compose.onNodeWithText("تسجيل الدخول").performClick()
        compose.runOnIdle { assertTrue(loginRequested) }
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

        compose.onNodeWithText("أقل وقت").performScrollTo()
        compose.onNodeWithText("أقصى وقت").performScrollTo()
        compose.onNodeWithText("قنص الطلبات بدون وجهة").performScrollTo()
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

        // Condition groups are open by default; the field is reachable without expanding anything.
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
    fun setup_guide_starts_with_operational_prerequisites() {
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
        // Operational readiness comes before offer filters in the production setup order.
        compose.onNodeWithText("تأكد من اشتراكك").assertIsDisplayed()
        compose.onNodeWithText("فتح حسابي").assertIsDisplayed()
        compose.onAllNodesWithText("قريب وسريع").assertCountEquals(0)
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
