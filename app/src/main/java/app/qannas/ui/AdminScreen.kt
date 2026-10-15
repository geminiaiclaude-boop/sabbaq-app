package app.qannas.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.qannas.BuildConfig
import java.io.ByteArrayInputStream
import java.io.IOException
import org.json.JSONObject

private const val ADMIN_ORIGIN = "https://qannas.local"
private const val ADMIN_HOST = "qannas.local"
private const val ADMIN_BASE_URL = "$ADMIN_ORIGIN/admin/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdminScreen() {
    if (!hasValidAdminConfiguration()) {
        AdminErrorState(
            message = "إعداد لوحة الإدارة غير مكتمل في هذه النسخة. ثبّت إصداراً مهيأً من مصدر موثوق ثم حاول مجدداً.",
            onRetry = null
        )
        return
    }

    var loadError by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val configScript = remember { adminConfigScript() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                createHardenedAdminWebView(context, configScript) { message ->
                    loadError = message
                }.also { webView ->
                    webView.tag = loadAttempt
                    webView.loadLocalAdminPage { message -> loadError = message }
                }
            },
            update = { webView ->
                if (webView.tag != loadAttempt) {
                    webView.tag = loadAttempt
                    loadError = null
                    webView.stopLoading()
                    webView.clearHistory()
                    webView.loadLocalAdminPage { message -> loadError = message }
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.clearHistory()
                webView.destroy()
            }
        )

        loadError?.let { message ->
            AdminErrorState(message = message, onRetry = { loadAttempt++ })
        }
    }
}

private fun hasValidAdminConfiguration(): Boolean {
    val endpoint = Uri.parse(BuildConfig.SUPABASE_URL)
    return endpoint.scheme == "https" &&
        endpoint.host?.endsWith(".supabase.co") == true &&
        (endpoint.host?.length ?: 0) > ".supabase.co".length &&
        endpoint.userInfo.isNullOrBlank() &&
        endpoint.port == -1 &&
        endpoint.query.isNullOrBlank() &&
        endpoint.fragment.isNullOrBlank() &&
        BuildConfig.SUPABASE_ANON_KEY.length > 20
}

private fun adminConfigScript(): String = """
    window.QANNAS_CONFIG = Object.freeze({
        supabaseUrl: ${JSONObject.quote(BuildConfig.SUPABASE_URL)},
        supabaseAnonKey: ${JSONObject.quote(BuildConfig.SUPABASE_ANON_KEY)}
    });
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
private fun createHardenedAdminWebView(
    context: Context,
    configScript: String,
    onMainFrameError: (String) -> Unit
): WebView = WebView(context).apply {
    settings.apply {
        // JavaScript مطلوب للوحة المحلية فقط؛ لا توجد واجهة JavaScript أصلية مضافة إلى WebView.
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isAllowedAdminNavigation(request.url)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            if (uri.host == ADMIN_HOST && uri.path?.startsWith("/admin/") == true) {
                return localAdminResource(context, uri.path.orEmpty(), configScript)
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                view.post {
                    onMainFrameError("تعذر تحميل لوحة الإدارة. تحقق من اتصال الإنترنت ثم أعد المحاولة.")
                }
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                view.post {
                    onMainFrameError("تعذر تحميل لوحة الإدارة. تحقق من اتصال الإنترنت ثم أعد المحاولة.")
                }
            }
        }
    }
}

private fun isAllowedAdminNavigation(uri: Uri): Boolean =
    uri.scheme == "https" && uri.host == ADMIN_HOST && uri.path?.startsWith("/admin/") == true

private fun localAdminResource(context: Context, path: String, configScript: String): WebResourceResponse {
    if (path == "/admin/config.js") {
        return WebResourceResponse(
            "application/javascript",
            "UTF-8",
            ByteArrayInputStream(configScript.toByteArray(Charsets.UTF_8))
        )
    }

    val assetName = when (path) {
        "/admin/index.html" -> "index.html"
        "/admin/styles.css" -> "styles.css"
        "/admin/app.js" -> "app.js"
        else -> return emptyAdminResource()
    }
    return try {
        WebResourceResponse(adminMimeType(assetName), "UTF-8", context.assets.open("admin/$assetName"))
    } catch (_: IOException) {
        emptyAdminResource()
    }
}

private fun adminMimeType(assetName: String): String = when {
    assetName.endsWith(".css") -> "text/css"
    assetName.endsWith(".js") -> "application/javascript"
    else -> "text/html"
}

private fun emptyAdminResource(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    ByteArrayInputStream(ByteArray(0))
)

private fun WebView.loadLocalAdminPage(onError: (String) -> Unit) {
    try {
        val html = context.assets.open("admin/index.html").bufferedReader().use { it.readText() }
        loadDataWithBaseURL(ADMIN_BASE_URL, html, "text/html", "UTF-8", null)
    } catch (_: IOException) {
        onError("تعذر فتح لوحة الإدارة المضمنة. أعد تثبيت التطبيق من مصدر موثوق ثم حاول مجدداً.")
    }
}

@Composable
private fun AdminErrorState(message: String, onRetry: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("تعذر فتح لوحة الإدارة", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                if (onRetry != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onRetry) { Text("إعادة المحاولة") }
                }
            }
        }
    }
}
