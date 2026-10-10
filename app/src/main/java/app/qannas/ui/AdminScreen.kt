package app.qannas.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.qannas.BuildConfig

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
                
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                
                val adminUrl = "file:///android_asset/admin/index.html?url=${BuildConfig.SUPABASE_URL}&key=${BuildConfig.SUPABASE_ANON_KEY}"
                loadUrl(adminUrl)
            }
        }
    )
}
