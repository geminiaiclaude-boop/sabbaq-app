package app.sabbaq

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The product is «سبّاق» (Sabbaq): no screen, resource, script or document may carry the retired name. */
class BrandNameTest {
    // Built from parts so this file does not match itself.
    private val retired = listOf("qan" + "nas", "قن" + "اص", "قن" + "ّاص", "sni" + "per")

    @Test
    fun the_retired_name_appears_nowhere_in_the_project() {
        val root = File("..").canonicalFile
        val scanned = listOf("app/src", "core/src", "admin", "scripts", "docs", "supabase", ".github", "README.md", "AGENTS.md", "CLAUDE.md",
            "settings.gradle.kts", "app/build.gradle.kts", "app/proguard-rules.pro")
            .map { File(root, it) }
            .filter { it.exists() }
            .flatMap { it.walkTopDown().filter(File::isFile).toList() }
            .filterNot { it.extension in setOf("ttf", "jar", "png", "webp") }
        assertTrue("nothing scanned", scanned.size > 50)
        val offenders = scanned.flatMap { file ->
            val lower = file.readText().lowercase()
            val path = file.relativeTo(root).invariantSeparatorsPath.lowercase()
            retired.filter { it in lower || it in path }.map { "${file.relativeTo(root)}: $it" }
        }
        assertTrue("retired name found:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun the_app_is_published_as_sabbaq() {
        // The brand reads «سبّاق» in Arabic and "Sabbaq" in English; each lives in its own resources.
        val arabic = File("src/main/res/values-ar/strings.xml").readText()
        assertTrue("<string name=\"app_name\">سبّاق</string>" in arabic)
        val english = File("src/main/res/values/strings.xml").readText()
        assertTrue("<string name=\"app_name\">Sabbaq</string>" in english)
        val gradle = File("build.gradle.kts").readText()
        assertTrue("applicationId = \"app.sabbaq.driver\"" in gradle)
        assertTrue("namespace = \"app.sabbaq\"" in gradle)
    }
}
