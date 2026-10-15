package com.nuvio.app.features.whatsnew

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.isDesktop
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_icon_original
import nuvio.composeapp.generated.resources.whats_new_section_fixed
import nuvio.composeapp.generated.resources.whats_new_section_improved
import nuvio.composeapp.generated.resources.whats_new_section_new
import nuvio.composeapp.generated.resources.whats_new_sheet_continue
import nuvio.composeapp.generated.resources.whats_new_sheet_title
import nuvio.composeapp.generated.resources.whats_new_version_label
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Bundled release notes: composeResources/files/changelog.json, newest first, keyed by version.
 * Same format as the Mobile app. Notes are shown as plain text, exactly as written in the file.
 */
@Serializable
data class BundledRelease(
    val version: String,
    val date: String? = null,
    val new: List<String> = emptyList(),
    val improved: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
)

@Serializable
private data class BundledChangelog(val releases: List<BundledRelease> = emptyList())

internal object ReleaseNotes {
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: List<BundledRelease>? = null

    private val currentVersion: String get() = AppVersionPolicy.displayVersionName
    private val currentKey: String get() = "$currentVersion(${AppVersionPolicy.displayVersionCode})"

    /**
     * Live GitHub release notes merged over the bundled changelog.json, newest first.
     * A live entry replaces the bundled one for the same version; bundled-only versions stay.
     * Offline, rate-limited or slow (4 s cap): bundled + whatever was cached last time.
     */
    suspend fun load(forceRefresh: Boolean = false): List<BundledRelease> =
        merge(LiveReleaseNotes.load(forceRefresh).map { it.cleaned() }, loadBundled())

    /** Bundled + cached live notes without touching the network. */
    suspend fun loadOffline(): List<BundledRelease> = merge(LiveReleaseNotes.cached().map { it.cleaned() }, loadBundled())

    internal fun merge(live: List<BundledRelease>, bundled: List<BundledRelease>): List<BundledRelease> {
        val byVersion = LinkedHashMap<String, BundledRelease>()
        bundled.forEach { byVersion[it.version] = it }
        live.forEach { l -> byVersion[l.version] = l.copy(date = l.date ?: byVersion[l.version]?.date) }
        return byVersion.values.sortedWith { a, b -> compareVersions(b.version, a.version) }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun loadBundled(): List<BundledRelease> {
        cached?.let { return it }
        val list = runCatching {
            json.decodeFromString<BundledChangelog>(Res.readBytes("files/changelog.json").decodeToString())
                .releases
                .map { it.cleaned() }
        }.getOrDefault(emptyList())
        cached = list
        return list
    }

    /** The release to show on this launch, or null when this version was already seen or has no notes. */
    suspend fun pendingForLaunch(): BundledRelease? {
        if (!isDesktop) return null
        if (WhatsNewStorage.loadLastSeen() == currentKey) return null
        val release = load().firstOrNull { it.version == currentVersion }
        if (release == null) markSeen()
        return release
    }

    fun markSeen() {
        WhatsNewStorage.saveLastSeen(currentKey)
    }
}

/** Removes escape leftovers (a backslash before quotes or apostrophes) so notes always render plainly. */
private fun String.plain(): String = replace("\\'", "'").replace("\\\"", "\"").trim()

private fun BundledRelease.cleaned() = copy(
    new = new.map { it.plain() }.filter { it.isNotEmpty() },
    improved = improved.map { it.plain() }.filter { it.isNotEmpty() },
    fixed = fixed.map { it.plain() }.filter { it.isNotEmpty() },
)

/** Shows the sheet once per new version or build, after the main UI is ready. */
@Composable
internal fun WhatsNewLaunchHost(enabled: Boolean) {
    var pending by remember { mutableStateOf<BundledRelease?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled || checked) return@LaunchedEffect
        checked = true
        kotlinx.coroutines.delay(900)
        pending = ReleaseNotes.pendingForLaunch()
    }
    val current = pending ?: return
    WhatsNewSheet(
        releases = listOf(current),
        onContinue = {
            ReleaseNotes.markSeen()
            pending = null
        },
    )
}

/** Settings > About > What's new: every bundled release, newest first. */
@Composable
internal fun BundledWhatsNewSheet(onDismiss: () -> Unit) {
    var releases by remember { mutableStateOf<List<BundledRelease>?>(null) }
    LaunchedEffect(Unit) {
        releases = ReleaseNotes.loadOffline()
        releases = ReleaseNotes.load(forceRefresh = true)
    }
    val loaded = releases ?: return
    WhatsNewSheet(releases = loaded, onContinue = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewSheet(releases: List<BundledRelease>, onContinue: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = MaterialTheme.nuvio.colors
    val headline = releases.firstOrNull()
    NuvioModalBottomSheet(onDismissRequest = onContinue, sheetState = sheetState, fullHeight = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.app_icon_original),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.whats_new_sheet_title, headline?.version ?: AppVersionPolicy.displayVersionName),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                if (headline != null) {
                    Text(
                        text = releaseSubtitle(headline),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 24.dp)
                        .padding(top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    releases.forEachIndexed { index, release ->
                        if (index > 0) {
                            Text(
                                text = stringResource(Res.string.whats_new_sheet_title, release.version),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Text(releaseSubtitle(release), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        ReleaseSections(release)
                    }
                }
                val background = colors.surfaceSheet
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                        .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)))),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                        .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))),
                )
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.whats_new_sheet_continue),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = nuvioSafeBottomPadding(16.dp)),
                onClick = onContinue,
            )
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatReleaseDate(iso: String?): String? {
    val parts = iso?.split("-")?.takeIf { it.size == 3 } ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return "$day ${MONTHS[month - 1]} ${parts[0]}"
}

@Composable
private fun releaseSubtitle(release: BundledRelease): String {
    val version = stringResource(Res.string.whats_new_version_label, release.version)
    return listOfNotNull(version, formatReleaseDate(release.date)).joinToString("  ·  ")
}

@Composable
private fun ReleaseSections(release: BundledRelease) {
    NotesSection(Icons.Rounded.AutoAwesome, stringResource(Res.string.whats_new_section_new), release.new)
    NotesSection(Icons.Rounded.Bolt, stringResource(Res.string.whats_new_section_improved), release.improved)
    NotesSection(Icons.Rounded.Build, stringResource(Res.string.whats_new_section_fixed), release.fixed)
}

@Composable
private fun NotesSection(icon: ImageVector, title: String, notes: List<String>) {
    if (notes.isEmpty()) return
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
        }
        notes.forEach { note ->
            Row(
                modifier = Modifier.padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.8f)),
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = colors.textPrimary.copy(alpha = 0.92f),
                )
            }
        }
    }
}
