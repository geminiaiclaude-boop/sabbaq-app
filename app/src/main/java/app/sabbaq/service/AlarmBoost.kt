package app.sabbaq.service

import android.content.Context
import android.media.AudioManager

/**
 * The alarm stream at full volume for a tone, shared by the accept tone and the excluded-zone
 * warning: the first raise remembers the driver's level, overlapping raises do not overwrite it
 * with the boosted one, and the last release restores it.
 */
object AlarmBoost {
    private var saved = -1
    private var holders = 0

    @Synchronized
    fun raise(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (holders == 0) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                saved = if (current < max) current else -1
                if (saved >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
            holders++
        }
    }

    @Synchronized
    fun release(context: Context) {
        if (holders == 0) return
        holders--
        if (holders > 0) return
        if (saved >= 0) runCatching { context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
        saved = -1
    }

    /** Service teardown: whatever is still raised goes back now. */
    @Synchronized
    fun releaseAll(context: Context) {
        if (holders == 0) return
        holders = 1
        release(context)
    }
}
