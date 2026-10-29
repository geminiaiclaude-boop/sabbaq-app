package app.sabbaq.core.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

/** FareSniper `targetApp`: which ride apps are watched; BOTH means all of them. */
@Serializable
enum class TargetApp { JEENY, BOLT, PETRA, BOTH }

enum class RideApp { JEENY, BOLT, PETRA }

/** FareSniper's package routing (`processAccessibilityEvent`, QANNAS_ENGINE_FACTS.md §5). */
object RideApps {
    const val JEENY_PACKAGE = "me.com.easytaxista"
    const val BOLT_PACKAGE = "ee.mtakso.driver"
    const val PETRA_PACKAGE = "com.petraride_captain"
    private val JEENY_WORDS = listOf("jeeny", "easytaxi", "taxi", "ride", "driver")

    /** The app a package belongs to when [target] watches it, else null. */
    fun of(packageName: String?, target: TargetApp): RideApp? {
        val p = packageName?.lowercase() ?: return null
        if (p.startsWith("app.sabbaq")) return null
        return when {
            p == BOLT_PACKAGE || "mtakso" in p -> RideApp.BOLT.takeIf { target == TargetApp.BOLT || target == TargetApp.BOTH }
            p == PETRA_PACKAGE || "petraride" in p -> RideApp.PETRA.takeIf { target == TargetApp.PETRA || target == TargetApp.BOTH }
            p == JEENY_PACKAGE || JEENY_WORDS.any { it in p } -> RideApp.JEENY.takeIf { target == TargetApp.JEENY || target == TargetApp.BOTH }
            else -> null
        }
    }

    /** Package names the accessibility service listens to for [target], plus Sabbaq's own (live accept test). */
    fun packages(target: TargetApp, ownPackage: String): Array<String> = ridePackages(target) + ownPackage

    private fun ridePackages(target: TargetApp): Array<String> = when (target) {
        TargetApp.JEENY -> arrayOf(JEENY_PACKAGE)
        TargetApp.BOLT -> arrayOf(BOLT_PACKAGE)
        TargetApp.PETRA -> arrayOf(PETRA_PACKAGE, "com.PetraRide_Captain")
        TargetApp.BOTH -> arrayOf(JEENY_PACKAGE, BOLT_PACKAGE, PETRA_PACKAGE, "com.PetraRide_Captain")
    }

    /** Reads one screen with the reader FareSniper uses for [app] (Jeeny: learned ids first). */
    fun read(
        app: RideApp,
        root: UiNode,
        screenHeight: Int,
        config: EngineConfig,
        learnedIds: LearnedOfferIds?,
        uptimeMs: () -> Long,
        hardDeadline: Long = 0L,
        idleDeadline: Long = 0L,
        abort: AtomicBoolean? = null,
        collectAddresses: Boolean = false,
        /** Receives why the screen gave no offer (reason, detail) when the result is null. */
        onMiss: ((String, String?) -> Unit)? = null,
    ): ParsedRide? = when (app) {
        RideApp.BOLT -> {
            val reader = BoltScreenReader(config)
            reader.read(root, screenHeight)?.copy(provider = "bolt")
                ?: run { onMiss?.invoke(reader.lastMiss ?: "unknown", null); null }
        }
        RideApp.PETRA -> {
            val reader = PetraScreenReader()
            reader.read(root, screenHeight)?.copy(provider = "petra")
                ?: run { onMiss?.invoke(reader.lastMiss ?: "unknown", null); null }
        }
        RideApp.JEENY -> {
            val reader = JeenyScreenReader(config, uptimeMs)
            learnedIds?.let { reader.readByLearnedIds(root, it) }
                ?: reader.read(root, screenHeight, collectAddresses, hardDeadline, idleDeadline, abort)
                ?: run { onMiss?.invoke(reader.lastMiss ?: "unknown", reader.lastMissDetail); null }
        }
    }
}
