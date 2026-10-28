package app.sabbaq.ui

import androidx.appcompat.app.AppCompatActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppCompatDelegate.setApplicationLocales only reaches AppCompatActivity on Android 8–12 (API < 33),
 * so a plain ComponentActivity would ignore the in-app language switch there. AppCompatActivity
 * in turn refuses to start without a Theme.AppCompat parent.
 */
class InAppLanguageSwitchTest {
    @Test
    fun every_activity_applies_the_in_app_language_on_older_android() {
        listOf(MainActivity::class.java, LiveAcceptTestActivity::class.java).forEach {
            assertTrue("${it.simpleName} must extend AppCompatActivity", AppCompatActivity::class.java.isAssignableFrom(it))
        }
    }

    @Test
    fun app_theme_is_an_appcompat_theme_in_every_variant() {
        val themes = File("src/main/res").listFiles { dir -> dir.name.startsWith("values") }.orEmpty()
            .map { File(it, "themes.xml") }.filter { it.exists() }
        assertTrue("no themes.xml found", themes.isNotEmpty())
        themes.forEach { file ->
            val parent = Regex("""name="Theme\.Sabbaq"\s+parent="([^"]+)"""").find(file.readText())?.groupValues?.get(1)
            assertTrue("${file.parentFile.name}: Theme.Sabbaq parent is $parent", parent?.startsWith("Theme.AppCompat") == true)
        }
    }
}
