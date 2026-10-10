package app.qannas.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.qannas.BuildConfig
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdminScreen() {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request?.url?.toString()?.endsWith("config.js") == true) {
                            val configJs = """
                                window.QANNAS_CONFIG = {
                                    supabaseUrl: '${BuildConfig.SUPABASE_URL}',
                                    supabaseAnonKey: '${BuildConfig.SUPABASE_ANON_KEY}'
                                };
                            """.trimIndent()
                            return WebResourceResponse(
                                "application/javascript",
                                "UTF-8",
                                ByteArrayInputStream(configJs.toByteArray(Charsets.UTF_8))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                webChromeClient = WebChromeClient()
                loadUrl("file:///android_asset/admin/index.html")
            }
        }
    )
}
