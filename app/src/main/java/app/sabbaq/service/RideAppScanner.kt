package app.sabbaq.service

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import app.sabbaq.core.RideAppDiscovery
import app.sabbaq.core.RideProfile

/**
 * Where the supported ride apps are on this phone. The accessibility service runs in the main
 * profile and can only read apps installed there under a listed package; a copy in a dual-app,
 * work or secure-folder profile, or a build under another package, is invisible to it and is
 * reported here so the driver and the administration see why no offer is read.
 */
object RideAppScanner {
    data class Scan(
        /** Listed packages present in the main profile: the only ones Sabbaq can read. */
        val readable: List<RideAppDiscovery.Candidate>,
        /** Ride apps in the main profile under a package the profiles do not list. */
        val unknownPackage: List<RideAppDiscovery.Candidate>,
        /** Ride apps found only in another profile of this user. */
        val otherProfile: List<RideAppDiscovery.Candidate>,
    ) {
        fun readable(provider: String): Boolean = readable.any { it.provider == provider }
    }

    private const val TTL_MS = 5 * 60 * 1000L
    @Volatile private var cached: Pair<Long, Scan>? = null

    fun scan(context: Context, profiles: List<RideProfile>, force: Boolean = false): Scan {
        val now = SystemClock.elapsedRealtime()
        cached?.let { (at, scan) -> if (!force && now - at < TTL_MS) return scan }
        val main = mainProfileApps(context)
        val classifiedMain = main.mapNotNull { (pkg, label) -> RideAppDiscovery.classify(pkg, label, profiles) }
        val readable = classifiedMain.filter { it.known } +
            listedButHidden(context, profiles, classifiedMain.map { it.packageName }.toSet())
        val unknown = classifiedMain.filterNot { it.known }
        val elsewhere = otherProfileApps(context)
            .mapNotNull { (pkg, label) -> RideAppDiscovery.classify(pkg, label, profiles) }
            .filter { candidate -> readable.none { it.provider == candidate.provider } }
        return Scan(readable.distinctBy { it.packageName }, unknown.distinctBy { it.packageName }, elsewhere.distinctBy { it.packageName })
            .also { cached = now to it }
    }

    @Suppress("DEPRECATION")
    private fun mainProfileApps(context: Context): List<Pair<String, String?>> = runCatching {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            else pm.queryIntentActivities(launcher, 0)
        activities.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            pkg to runCatching { info.loadLabel(pm).toString() }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** A listed package without a launcher entry (or hidden from the launcher query) still counts. */
    @Suppress("DEPRECATION")
    private fun listedButHidden(context: Context, profiles: List<RideProfile>, seen: Set<String>): List<RideAppDiscovery.Candidate> =
        profiles.flatMap { profile -> profile.packages.map { profile.id to it } }
            .filter { (_, pkg) -> pkg !in seen }
            .filter { (_, pkg) ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                    else context.packageManager.getPackageInfo(pkg, 0)
                }.isSuccess
            }
            .map { (provider, pkg) -> RideAppDiscovery.Candidate(provider, pkg, known = true) }

    private fun otherProfileApps(context: Context): List<Pair<String, String?>> = runCatching {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val me = Process.myUserHandle()
        launcherApps.profiles.filter { it != me }.flatMap { profile ->
            runCatching { launcherApps.getActivityList(null, profile) }.getOrDefault(emptyList()).map { activity ->
                activity.applicationInfo.packageName to runCatching { activity.label?.toString() }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())
}
