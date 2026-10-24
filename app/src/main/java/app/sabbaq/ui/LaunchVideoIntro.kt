package app.sabbaq.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sabbaq.R
import app.sabbaq.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** Brand-led cinematic opening: the clip is only a visual layer behind the app identity. */
@Composable
internal fun SabbaqVideoOpening(onFinished: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reducedMotion = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    var hasFinished by remember { mutableStateOf(false) }
    val textureView = remember(context) { TextureView(context).apply { isOpaque = false } }
    val reveal = remember { Animatable(if (reducedMotion) 1f else 0f) }

    fun finishOnce() {
        if (!hasFinished) {
            hasFinished = true
            finish()
        }
    }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) finishOnce()
    }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) reveal.animateTo(1f, tween(1_150, easing = FastOutSlowInEasing))
    }

    DisposableEffect(textureView, lifecycleOwner, reducedMotion) {
        if (reducedMotion) return@DisposableEffect onDispose { }

        var player: MediaPlayer? = null
        var surface: Surface? = null
        var isPrepared = false
        var ended = false

        fun releasePlayer() {
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            runCatching { player?.stop() }
            player?.release()
            player = null
            surface?.release()
            surface = null
            isPrepared = false
        }

        fun fitVideoToView(view: TextureView, videoWidth: Int, videoHeight: Int) {
            if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
            val source = RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat())
            val scale = maxOf(view.width.toFloat() / videoWidth, view.height.toFloat() / videoHeight)
            val scaledWidth = videoWidth * scale
            val scaledHeight = videoHeight * scale
            val destination = RectF(
                (view.width - scaledWidth) / 2f,
                (view.height - scaledHeight) / 2f,
                (view.width + scaledWidth) / 2f,
                (view.height + scaledHeight) / 2f,
            )
            view.setTransform(Matrix().apply { setRectToRect(source, destination, Matrix.ScaleToFit.FILL) })
        }

        val textureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                runCatching {
                    val playbackSurface = Surface(texture)
                    val media = MediaPlayer()
                    surface = playbackSurface
                    player = media
                    media.setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.sabbaq_cinematic_intro}"))
                    media.setSurface(playbackSurface)
                    media.isLooping = false
                    media.setVolume(0f, 0f)
                    media.setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                        fitVideoToView(textureView, videoWidth, videoHeight)
                    }
                    media.setOnPreparedListener { prepared ->
                        isPrepared = true
                        fitVideoToView(textureView, prepared.videoWidth, prepared.videoHeight)
                        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !ended) {
                            runCatching { prepared.start() }.onFailure { finishOnce() }
                        }
                    }
                    media.setOnCompletionListener {
                        ended = true
                        finishOnce()
                    }
                    media.setOnErrorListener { _, _, _ ->
                        ended = true
                        finishOnce()
                        true
                    }
                    media.prepareAsync()
                }.onFailure {
                    ended = true
                    finishOnce()
                }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                player?.let { fitVideoToView(textureView, it.videoWidth, it.videoHeight) }
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        textureView.surfaceTextureListener = textureListener
        textureView.surfaceTexture?.let { textureListener.onSurfaceTextureAvailable(it, textureView.width, textureView.height) }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (isPrepared && !ended) runCatching { player?.start() }
                Lifecycle.Event.ON_STOP -> if (player?.isPlaying == true) runCatching { player?.pause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            textureView.surfaceTextureListener = null
            releasePlayer()
        }
    }

    // A decode failure, unsupported device, or stuck prepare must never hold up app entry.
    LaunchedEffect(textureView, reducedMotion) {
        if (!reducedMotion) {
            delay(12_000)
            finishOnce()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.sabbaq_intro_poster),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.48f),
                    0.32f to Color.Black.copy(alpha = 0.08f),
                    0.58f to Color.Black.copy(alpha = 0.12f),
                    1f to Color.Black.copy(alpha = 0.58f),
                ),
            ),
        )
        SabbaqRadarScene(
            active = true,
            pins = false,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.graphicsLayer {
                    alpha = reveal.value
                    scaleX = 0.84f + reveal.value * 0.16f
                    scaleY = 0.84f + reveal.value * 0.16f
                    translationY = (1f - reveal.value) * 22.dp.toPx()
                },
            ) {
                SabbaqBrand(compact = false, onHero = true)
            }
        }
    }
}

/** Private preference: the cinematic opening is not repeated on every app start. */
internal object LaunchVideoPreferences {
    private const val PREFS = "sabbaq_launch_experience"
    private const val KEY_VIDEO_SEEN = "cinematic_opening_seen_v2"

    fun shouldShow(context: Context): Boolean = runCatching {
        !context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIDEO_SEEN, false)
    }.getOrDefault(false)

    fun markSeen(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VIDEO_SEEN, true).commit()
        }
    }
}
