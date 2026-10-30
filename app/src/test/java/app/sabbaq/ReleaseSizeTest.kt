package app.sabbaq

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct APK is downloaded in full by every driver on every update, mostly over mobile data.
 * Native libraries (the map's, 26.7 MB for two CPU types) must stay compressed in it: about 16 MB
 * per download instead of 33.6 MB, with the same space on the phone. Owner's decision, 2026-10-30.
 */
class ReleaseSizeTest {
    private val build = File("build.gradle.kts").readText()

    @Test
    fun native_libraries_stay_compressed_in_the_apk() {
        assertTrue("keep jniLibs.useLegacyPackaging = true in app/build.gradle.kts",
            Regex("""jniLibs\.useLegacyPackaging\s*=\s*true""").containsMatchIn(build))
    }

    @Test
    fun the_release_keeps_only_the_arm_cpu_types() {
        assertTrue("the release keeps arm64-v8a and armeabi-v7a only",
            Regex("""abiFilters\s*\+=\s*listOf\("arm64-v8a",\s*"armeabi-v7a"\)""").containsMatchIn(build))
    }
}
