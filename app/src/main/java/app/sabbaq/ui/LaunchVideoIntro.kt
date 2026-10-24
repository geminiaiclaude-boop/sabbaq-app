package app.sabbaq.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sabbaq.R
import app.sabbaq.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** Plays the supplied local clip once on a fresh install/update, then lets the existing logo intro run. */
@Composable
internal fun SabbaqVideoOpening(onFinished: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reducedMotion = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    var soundEnabled by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var hasFinished by remember { mutableStateOf(false) }
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    val latestSoundEnabled by rememberUpdatedState(soundEnabled)
    val videoView = remember(context) { VideoView(context).apply { setBackgroundColor(android.graphics.Color.BLACK) } }

    fun finishOnce() {
        if (!hasFinished) {
            hasFinished = true
            finish()
        }
    }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) finishOnce()
    }

    DisposableEffect(videoView, lifecycleOwner, reducedMotion) {
        if (reducedMotion) return@DisposableEffect onDispose { videoView.stopPlayback() }

        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            player.setVolume(if (latestSoundEnabled) 1f else 0f, if (latestSoundEnabled) 1f else 0f)
            mediaPlayer.value = player
            isPrepared = true
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                videoView.start()
            }
        }
        videoView.setOnCompletionListener { finishOnce() }
        videoView.setOnErrorListener { _, _, _ ->
            // Media failure must never block app entry; the existing logo intro follows.
            finishOnce()
            true
        }
        runCatching {
            videoView.setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.sabbaq_cinematic_intro}"))
        }.onFailure { finishOnce() }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (isPrepared && !hasFinished) videoView.start()
                Lifecycle.Event.ON_STOP -> if (videoView.isPlaying) videoView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            videoView.setOnPreparedListener(null)
            videoView.setOnCompletionListener(null)
            videoView.setOnErrorListener(null)
            videoView.stopPlayback()
            mediaPlayer.value = null
        }
    }

    // Some devices can fail to report prepare/completion; fail open after a bounded interval.
    LaunchedEffect(videoView, reducedMotion) {
        if (!reducedMotion) {
            delay(15_000)
            if (!isPrepared || !hasFinished) finishOnce()
        }
    }

    LaunchedEffect(soundEnabled, isPrepared) {
        val volume = if (soundEnabled) 1f else 0f
        mediaPlayer.value?.setVolume(volume, volume)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { videoView.apply { contentDescription = "افتتاحية مرئية لسبّاق" } },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "فيديو افتتاحي لسبّاق" },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.42f),
                    0.28f to Color.Transparent,
                    0.56f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.88f),
                ),
            ),
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { soundEnabled = !soundEnabled }) {
                Icon(
                    imageVector = if (soundEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                    contentDescription = if (soundEnabled) "كتم صوت الافتتاحية" else "تشغيل صوت الافتتاحية",
                    tint = Color.White,
                )
            }
        }
        TextButton(
            onClick = ::finishOnce,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 16.dp, top = 8.dp),
        ) {
            Text("تخطي الفيديو", color = Color.White)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "سبّاق",
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "الطلب المناسب، على شروطك.",
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "اختر شروطك، ودع سبّاق يتابع الطلبات المطابقة.",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Small private preference: do not replay the cinematic clip on every app start. */
internal object LaunchVideoPreferences {
    private const val PREFS = "sabbaq_launch_experience"
    private const val KEY_VIDEO_SEEN = "cinematic_opening_seen_v1"

    fun shouldShow(context: Context): Boolean = runCatching {
        !context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIDEO_SEEN, false)
    }.getOrDefault(false)

    fun markSeen(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VIDEO_SEEN, true).commit()
        }
    }
}
