package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_github_api_error
import org.jetbrains.compose.resources.getString

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

internal class NoChannelReleaseException : IllegalStateException()

internal data class AppUpdateAssetCandidate(
    val name: String,
    val downloadUrl: String,
    val size: Long? = null,
    val contentType: String? = null,
)

internal fun selectBestUpdateAsset(
    assets: List<AppUpdateAssetCandidate>,
    selector: AppUpdateAssetSelector,
): AppUpdateAssetCandidate? {
    val updateAssets = assets.filter { asset ->
        selector.fileExtensions.any { extension -> asset.name.endsWith(extension, ignoreCase = true) } ||
            selector.contentTypes.any { it.equals(asset.contentType, ignoreCase = true) }
    }
    if (updateAssets.isEmpty()) return null
    if (updateAssets.size == 1) return updateAssets.first()

    for (fragment in selector.preferredNameFragments) {
        updateAssets.firstOrNull { it.name.contains(fragment, ignoreCase = true) }?.let { return it }
    }
    return updateAssets.firstOrNull { asset ->
        selector.fallbackNameFragments.any { asset.name.contains(it, ignoreCase = true) }
    } ?: updateAssets.first()
}

internal object AppUpdaterRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> {
        val source = AppUpdaterPlatform.releaseSource
        if (source.forkReleaseFeed) {
            return getForkReleaseUpdate(source)
        }
        return runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = "https://api.github.com/repos/${source.owner}/${source.repo}/${releasePath(channel)}",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to source.userAgent,
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        selectUpdate(
            responseBody = response.body,
            channel = channel,
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
            selector = AppUpdaterPlatform.assetSelector,
        )
            ?: throw NoChannelReleaseException()
        }
    }

    private suspend fun getForkReleaseUpdate(source: AppUpdateReleaseSource): Result<AppUpdate> = runCatching {
        val url = ForkUpdateFeed.requestUrl(
            if (AppUpdaterPlatform.updateChecksEnabled) {
                DesktopUpdateChannel.RELEASE
            } else {
                DesktopUpdateChannel.INTEGRATION
            },
        ) ?: throw NoChannelReleaseException()
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = ForkUpdateFeed.requestHeaders(source.userAgent),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        selectForkUpdate(
            responseBody = response.body,
            currentVersion = AppUpdaterPlatform.currentVersionName,
            selector = AppUpdaterPlatform.assetSelector,
        ) ?: throw NoChannelReleaseException()
    }

    internal fun releasePath(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.STABLE -> "releases/latest"
        UpdateChannel.BETA -> "releases?per_page=100"
        UpdateChannel.ALL_RELEASES -> "releases?per_page=20"
    }

    internal fun selectUpdate(
        responseBody: String,
        channel: UpdateChannel,
        supportedAbis: List<String>,
        selector: AppUpdateAssetSelector = AppUpdateAssetSelector(
            fileExtensions = listOf(".apk"),
            contentTypes = listOf("application/vnd.android.package-archive"),
            preferredNameFragments = supportedAbis,
            fallbackNameFragments = listOf("universal", "all"),
        ),
    ): AppUpdate? {
        val releases = when (channel) {
            UpdateChannel.STABLE -> listOf(json.decodeFromString<GitHubReleaseDto>(responseBody))
            UpdateChannel.BETA, UpdateChannel.ALL_RELEASES ->
                json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
        }
        val eligibleReleases = ReleaseSelector.eligibleReleases(releases, channel)
        val releasesToCheck = if (channel == UpdateChannel.ALL_RELEASES) {
            eligibleReleases.take(1)
        } else {
            eligibleReleases
        }
        return releasesToCheck.firstNotNullOfOrNull { release ->
            val asset = selectBestUpdateAsset(
                assets = release.assets.map { candidate ->
                    AppUpdateAssetCandidate(
                        name = candidate.name,
                        downloadUrl = candidate.browserDownloadUrl,
                        size = candidate.size,
                        contentType = candidate.contentType,
                    )
                },
                selector = selector,
            ) ?: return@firstNotNullOfOrNull null
            val tag = if (channel == UpdateChannel.ALL_RELEASES) {
                release.tagName?.takeIf(String::isNotBlank)
                    ?: release.name?.takeIf(String::isNotBlank)
                    ?: return@firstNotNullOfOrNull null
            } else {
                release.tagName?.takeIf { VersionUtils.parse(it) != null }
                    ?: release.name.orEmpty()
            }
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.downloadUrl,
                assetSizeBytes = asset.size,
            )
        }
    }

    /**
     * Picks the newest release on this fork that is strictly newer than [currentVersion]
     * and has an installer for this computer. No assets, no parsable tags, or only
     * older / other-line tags return null so the check can report up to date.
     */
    internal fun selectForkUpdate(
        responseBody: String,
        currentVersion: String,
        selector: AppUpdateAssetSelector,
    ): AppUpdate? {
        val trimmed = responseBody.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return null
        val releases = json.decodeFromString<List<GitHubReleaseDto>>(trimmed)
        return releases
            .asSequence()
            .filterNot(GitHubReleaseDto::draft)
            .mapNotNull { release ->
                val tag = release.tagName?.takeIf { VersionUtils.parse(it) != null }
                    ?: release.name?.takeIf { VersionUtils.parse(it) != null }
                    ?: return@mapNotNull null
                if (!VersionUtils.isRemoteNewer(tag, currentVersion)) return@mapNotNull null
                val asset = selectBestUpdateAsset(
                    assets = release.assets.mapNotNull { candidate ->
                        if (!ForkUpdateFeed.isForkAssetUrl(candidate.browserDownloadUrl)) return@mapNotNull null
                        AppUpdateAssetCandidate(
                            name = candidate.name,
                            downloadUrl = candidate.browserDownloadUrl,
                            size = candidate.size,
                            contentType = candidate.contentType,
                        )
                    },
                    selector = selector,
                ) ?: return@mapNotNull null
                val version = VersionUtils.parse(tag) ?: return@mapNotNull null
                ForkReleaseCandidate(version, release, tag, asset)
            }
            .sortedByDescending { it.version }
            .firstOrNull()
            ?.let { candidate ->
                AppUpdate(
                    tag = candidate.tag,
                    title = candidate.release.name?.takeIf { it.isNotBlank() } ?: candidate.tag,
                    notes = candidate.release.body.orEmpty(),
                    releaseUrl = candidate.release.htmlUrl,
                    assetName = candidate.asset.name,
                    assetUrl = candidate.asset.downloadUrl,
                    assetSizeBytes = candidate.asset.size,
                )
            }
    }

    private data class ForkReleaseCandidate(
        val version: SemanticVersion,
        val release: GitHubReleaseDto,
        val tag: String,
        val asset: AppUpdateAssetCandidate,
    )
}
