package app.sabbaq.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sabbaq.core.Profiles
import app.sabbaq.service.ProfileRepository
import app.sabbaq.service.RideAppScanner
import app.sabbaq.ui.theme.sabbaqEntrance
import app.sabbaq.ui.theme.tilt3D

/**
 * The ride-hailing apps Sabbaq works with, read from the same bundled profiles the
 * acceptance engine uses, so what the driver sees is exactly what is supported. Names only:
 * no third-party logos are used.
 */
internal data class SupportedPlatform(val id: String, val brand: String, val packages: List<String>, val tint: Color)

internal object SupportedPlatforms {
    private val tints = mapOf("bolt" to Color(0xFF34D186), "jeeny" to Color(0xFF7B61FF), "petra" to Color(0xFFE0A33A))

    val all: List<SupportedPlatform> by lazy {
        runCatching { Profiles.bundled() }.getOrDefault(emptyList()).map { profile ->
            SupportedPlatform(
                id = profile.id,
                brand = brandOf(profile.name),
                packages = profile.packages,
                tint = tints[profile.id] ?: Color(0xFFFFD21F),
            )
        }
    }

    /** "Bolt Driver" → "Bolt", "PetraRide Captain" → "PetraRide": the brand drivers know. */
    fun brandOf(profileName: String): String =
        profileName.replace(Regex("\\s+(Driver|Captain|Partner)$", RegexOption.IGNORE_CASE), "").trim()

    /** Where a platform is on this phone, as far as Sabbaq's accessibility service is concerned. */
    enum class Presence { READABLE, OTHER_PROFILE, UNKNOWN_PACKAGE, MISSING }

    fun presence(context: Context): Map<String, Presence> {
        val scan = RideAppScanner.scan(context, ProfileRepository.current())
        return all.associate { platform ->
            platform.id to when {
                scan.readable(platform.id) -> Presence.READABLE
                scan.otherProfile.any { it.provider == platform.id } -> Presence.OTHER_PROFILE
                scan.unknownPackage.any { it.provider == platform.id } -> Presence.UNKNOWN_PACKAGE
                else -> Presence.MISSING
            }
        }
    }
}

/**
 * «يعمل مع»: one raised tile per platform with its initial on a coloured coin. With
 * [detectInstalled] each tile also says whether that app is on this phone.
 */
@Composable
internal fun SupportedPlatformsRow(modifier: Modifier = Modifier, detectInstalled: Boolean = false, onHero: Boolean = false) {
    val context = LocalContext.current
    val platforms = SupportedPlatforms.all
    if (platforms.isEmpty()) return
    val presence = remember(detectInstalled) {
        if (detectInstalled) runCatching { SupportedPlatforms.presence(context) }.getOrDefault(emptyMap()) else emptyMap()
    }
    val colors = MaterialTheme.colorScheme
        val worksWithDesc = stringResource(R.string.platforms_works_with_desc, platforms.joinToString(stringResource(R.string.list_separator)) { it.brand })
    Row(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = worksWithDesc },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        platforms.forEachIndexed { index, platform ->
            val state = presence[platform.id] ?: SupportedPlatforms.Presence.MISSING
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (onHero) Color.White.copy(alpha = 0.12f) else colors.surface,
                contentColor = if (onHero) Color.White else colors.onSurface,
                shadowElevation = if (onHero) 0.dp else 8.dp,
                modifier = Modifier
                    .weight(1f)
                    .sabbaqEntrance(index)
                    .tilt3D(maxDegrees = 10f, idle = false, restX = 6f),
            ) {
                Column(Modifier.padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .shadow(8.dp, CircleShape, spotColor = platform.tint)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(platform.tint, platform.tint.copy(alpha = 0.7f)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(platform.brand.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                    Text(platform.brand, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 1)
                    if (detectInstalled) when (state) {
                        SupportedPlatforms.Presence.READABLE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.platform_installed), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                        }
                        SupportedPlatforms.Presence.OTHER_PROFILE ->
                            Text(stringResource(R.string.platform_other_profile), style = MaterialTheme.typography.labelSmall, color = colors.error, textAlign = TextAlign.Center)
                        SupportedPlatforms.Presence.UNKNOWN_PACKAGE ->
                            Text(stringResource(R.string.platform_unsupported_version), style = MaterialTheme.typography.labelSmall, color = colors.error, textAlign = TextAlign.Center)
                        SupportedPlatforms.Presence.MISSING ->
                            Text(stringResource(R.string.platform_not_installed), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A card for the dashboard: what is supported, what is installed, and what to do if none is. */
@Composable
internal fun SupportedPlatformsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val presence = remember { runCatching { SupportedPlatforms.presence(context) }.getOrDefault(emptyMap()) }
    val conj = stringResource(R.string.list_conjunction)
    fun brands(state: SupportedPlatforms.Presence) =
        SupportedPlatforms.all.filter { presence[it.id] == state }.joinToString(conj) { it.brand }
    val otherProfile = brands(SupportedPlatforms.Presence.OTHER_PROFILE)
    val unknownPackage = brands(SupportedPlatforms.Presence.UNKNOWN_PACKAGE)
    val anyReadable = presence.values.any { it == SupportedPlatforms.Presence.READABLE }
    SabbaqCard(
        title = stringResource(R.string.platforms_card_title),
        subtitle = if (anyReadable) stringResource(R.string.platforms_card_readable)
            else stringResource(R.string.platforms_card_none),
        modifier = modifier,
    ) {
        SupportedPlatformsRow(detectInstalled = true)
        if (otherProfile.isNotEmpty()) SabbaqMessage(
            stringResource(R.string.platforms_other_profile, otherProfile),

            isError = true,
        )
        if (unknownPackage.isNotEmpty()) SabbaqMessage(
            stringResource(R.string.platforms_unknown_package, unknownPackage),
            isError = true,
        )
    }
}
