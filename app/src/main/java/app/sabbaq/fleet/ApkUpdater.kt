package app.sabbaq.fleet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import app.sabbaq.BuildConfig
import app.sabbaq.core.AppRelease
import app.sabbaq.ui.PublicLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the published release. The direct build downloads the APK, checks its size, SHA-256,
 * package name, version code and that it is signed with the same certificate as the installed
 * app, then hands it to Android's installer (the driver confirms). The Play build opens the store.
 */
object ApkUpdater {
    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int) : State
        data class Ready(val file: File, val code: Long) : State
        data class Failed(val reason: String) : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val progress: StateFlow<State> = state.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Starts the update the way this build allows; returns false when nothing could be started. */
    suspend fun update(context: Context, release: AppRelease): Boolean {
        if (!BuildConfig.DIRECT_DISTRIBUTION || !release.downloadable) {
            PublicLinks.open(context, "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}")
            return true
        }
        val ready = (state.value as? State.Ready)?.takeIf { it.code == release.latestCode && it.file.isFile }
            ?: download(context, release) ?: return false
        return install(context, ready.file)
    }

    private suspend fun download(context: Context, release: AppRelease): State.Ready? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "sabbaq-${release.latestCode}.apk")
        state.value = State.Downloading(0)
        UpdateTelemetry.state("download_started", release.latestCode)
        val failure = runCatching {
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching "http_${response.code}"
                val body = response.body ?: return@runCatching "empty"
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > release.apkSize) return@runCatching "too_large"
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            state.value = State.Downloading((read * 100 / release.apkSize).toInt().coerceIn(0, 100))
                        }
                    }
                }
                when {
                    read != release.apkSize -> "size_mismatch"
                    digest.digest().joinToString("") { "%02x".format(it) } != release.apkSha256 -> "hash_mismatch"
                    else -> verifyPackage(context, file, release.latestCode)
                }
            }
        }.getOrElse { "network" }
        if (failure != null) {
            file.delete()
            state.value = State.Failed(failure)
            UpdateTelemetry.state("verify_failed:$failure", release.latestCode)
            return@withContext null
        }
        UpdateTelemetry.state("downloaded", release.latestCode)
        State.Ready(file, release.latestCode).also { state.value = it }
    }

    /** Null when the APK is this app, the published version and signed by the same certificate. */
    private fun verifyPackage(context: Context, file: File, code: Long): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "not_an_apk"
        if (archive.packageName != context.packageName) return "other_package"
        if (versionCode(archive) != code) return "version_mismatch"
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return null
        val theirs = signers(archive)
        val ours = signers(installed)
        // Android refuses a differently signed update anyway; this check only fails closed when both are readable.
        return if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours) "signature_mismatch" else null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return raw.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            UpdateTelemetry.state("needs_install_permission", 0)
            return runCatching {
                context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        UpdateTelemetry.state("install_started", 0)
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
