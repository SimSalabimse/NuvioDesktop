package com.nuvio.app.features.analyzeddetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.settings.SettingsSecretTextField
import com.nuvio.app.features.settings.SettingsSection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_sync_description
import nuvio.composeapp.generated.resources.settings_sync_create
import nuvio.composeapp.generated.resources.settings_sync_create_title
import nuvio.composeapp.generated.resources.settings_sync_device
import nuvio.composeapp.generated.resources.settings_sync_enroll_secret
import nuvio.composeapp.generated.resources.settings_sync_join
import nuvio.composeapp.generated.resources.settings_sync_join_code
import nuvio.composeapp.generated.resources.settings_sync_join_title
import nuvio.composeapp.generated.resources.settings_sync_last
import nuvio.composeapp.generated.resources.settings_sync_leave
import nuvio.composeapp.generated.resources.settings_sync_leave_description
import nuvio.composeapp.generated.resources.settings_sync_not_yet
import nuvio.composeapp.generated.resources.settings_sync_now
import nuvio.composeapp.generated.resources.settings_sync_passphrase
import nuvio.composeapp.generated.resources.settings_sync_server_url
import nuvio.composeapp.generated.resources.settings_sync_status
import nuvio.composeapp.generated.resources.settings_sync_syncing
import nuvio.composeapp.generated.resources.settings_sync_store_file
import nuvio.composeapp.generated.resources.settings_sync_store_macos
import nuvio.composeapp.generated.resources.settings_sync_store_windows
import nuvio.composeapp.generated.resources.settings_sync_what
import nuvio.composeapp.generated.resources.settings_sync_what_body
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AnalyzedDetailsSyncSettingsPage(isTablet: Boolean, preview: SyncSession? = null) {
    val liveSession by AnalyzedDetailsSyncService.state.collectAsStateWithLifecycle()
    val liveBusy by AnalyzedDetailsSyncService.busy.collectAsStateWithLifecycle()
    val session = preview ?: liveSession
    val busy = if (preview == null) liveBusy else false
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var enrollSecret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var joinCode by rememberSaveable { mutableStateOf("") }
    var joinPassphrase by remember { mutableStateOf("") }
    val gap = if (isTablet) 20.dp else 16.dp
    val clipboard = LocalClipboardManager.current
    val secretClipboard = remember(clipboard) { ReadOnlyClipboard(clipboard) }

    LaunchedEffect(session.joined) {
        if (session.joined) {
            enrollSecret = ""
            passphrase = ""
            joinPassphrase = ""
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text(
            text = stringResource(Res.string.compose_settings_root_sync_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!session.joined) {
            SettingsSection(title = stringResource(Res.string.settings_sync_create_title), isTablet = isTablet) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_sync_server_url)) },
                        supportingText = { Text("https://nuvio-sync.tullebukktil1000.workers.dev") },
                        enabled = !busy,
                    )
                    CompositionLocalProvider(LocalClipboardManager provides secretClipboard) {
                        SettingsSecretTextField(
                            value = enrollSecret,
                            onValueChange = { enrollSecret = it },
                            label = stringResource(Res.string.settings_sync_enroll_secret),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                        SettingsSecretTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = stringResource(Res.string.settings_sync_passphrase),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                    }
                    Button(
                        onClick = { AnalyzedDetailsSyncService.create(serverUrl, enrollSecret, passphrase) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(Res.string.settings_sync_create))
                    }
                }
            }
            SettingsSection(title = stringResource(Res.string.settings_sync_join_title), isTablet = isTablet) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_sync_server_url)) },
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_sync_join_code)) },
                        enabled = !busy,
                    )
                    CompositionLocalProvider(LocalClipboardManager provides secretClipboard) {
                        SettingsSecretTextField(
                            value = joinPassphrase,
                            onValueChange = { joinPassphrase = it },
                            label = stringResource(Res.string.settings_sync_passphrase),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        )
                    }
                    Button(
                        onClick = { AnalyzedDetailsSyncService.join(serverUrl, joinCode, joinPassphrase) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(Res.string.settings_sync_join))
                    }
                }
            }
        } else {
            SettingsSection(title = stringResource(Res.string.settings_sync_status), isTablet = isTablet) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val status = if (busy) stringResource(Res.string.settings_sync_syncing) else session.statusText
                    Text(
                        text = status,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            if (!busy && session.statusError) error(status)
                        },
                        color = if (!busy && session.statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(stringResource(Res.string.settings_sync_last) + ": " + lastSyncLabel(session.lastSuccessfulSyncMs))
                    Text(stringResource(Res.string.settings_sync_device) + ": " + session.deviceId)
                    Text(syncServerHost(session.baseUrl))
                    Text(storeLabel(session.secretStoreKind))
                    Text(stringResource(Res.string.settings_sync_join_code))
                    SelectionContainer {
                        Text(session.joinCode())
                    }
                    Button(onClick = { AnalyzedDetailsSyncService.syncNow() }, enabled = !busy) {
                        Text(stringResource(Res.string.settings_sync_now))
                    }
                }
            }
            SettingsSection(title = stringResource(Res.string.settings_sync_leave), isTablet = isTablet) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.settings_sync_leave_description))
                    Button(onClick = { AnalyzedDetailsSyncService.leave() }, enabled = !busy) {
                        Text(stringResource(Res.string.settings_sync_leave))
                    }
                }
            }
        }
        SettingsSection(title = stringResource(Res.string.settings_sync_what), isTablet = isTablet) {
            Text(stringResource(Res.string.settings_sync_what_body))
        }
    }
}

@Composable
private fun lastSyncLabel(epochMs: Long?): String {
    if (epochMs == null) return stringResource(Res.string.settings_sync_not_yet)
    val formatted = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    return formatted
}

@Composable
private fun storeLabel(kind: String): String = when (kind) {
    "macos-keychain" -> stringResource(Res.string.settings_sync_store_macos)
    "windows-credential-manager" -> stringResource(Res.string.settings_sync_store_windows)
    else -> stringResource(Res.string.settings_sync_store_file)
}

/** Paste still works. Copy and cut do not, so a passphrase cannot reach the clipboard. */
internal class ReadOnlyClipboard(private val delegate: ClipboardManager) : ClipboardManager {
    override fun getText(): AnnotatedString? = delegate.getText()
    override fun setText(annotatedString: AnnotatedString) = Unit
    override fun hasText(): Boolean = delegate.getText()?.isNotEmpty() == true
}

private fun SyncSession.joinCode(): String {
    val salt = SyncCrypto.decodeB64(saltBase64) ?: return ""
    val check = SyncCrypto.decodeB64(keyCheckBase64) ?: return ""
    return SyncCrypto.joinCode(spaceId, salt, check)
}
