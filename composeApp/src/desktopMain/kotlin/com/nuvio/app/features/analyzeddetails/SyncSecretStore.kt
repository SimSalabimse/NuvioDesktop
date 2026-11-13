package com.nuvio.app.features.analyzeddetails

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrases go to the OS store on macOS (login keychain via `security`) and
 * Windows (Credential Manager via CredWrite). The command output is not logged.
 * On other systems, including this Linux environment, the passphrase is stored
 * in an AES-256-GCM file whose key is a random 32-byte file created beside it
 * with owner-only permissions. That file is not the sync format and it is not
 * a substitute for the OS store when the OS store is available.
 */
internal interface SyncSecretStore {
    val kind: String
    fun get(account: String): String?
    fun put(account: String, secret: String)
    fun delete(account: String)
}

internal class MemorySecretStore : SyncSecretStore {
    override val kind: String = "memory"
    private val values = linkedMapOf<String, String>()
    override fun get(account: String): String? = values[account]
    override fun put(account: String, secret: String) { values[account] = secret }
    override fun delete(account: String) { values.remove(account) }
}

internal class EncryptedFileSecretStore(
    private val directory: Path,
    private val random: SecureRandom = SecureRandom(),
) : SyncSecretStore {
    override val kind: String = "encrypted-file"

    override fun get(account: String): String? {
        val encoded = readValues()[account] ?: return null
        val blob = SyncCrypto.decodeB64(encoded) ?: return null
        if (blob.size < 12 + 16) return null
        val nonce = blob.copyOfRange(0, 12)
        val cipherBytes = blob.copyOfRange(12, blob.size)
        return decrypt(nonce, account, cipherBytes)?.decodeToString()
    }

    override fun put(account: String, secret: String) {
        val values = readValues()
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = encrypt(nonce, account, secret.encodeToByteArray())
        values[account] = SyncCrypto.encodeB64(nonce + encrypted)
        writeValues(values)
    }

    override fun delete(account: String) {
        val values = readValues()
        if (values.remove(account) != null) writeValues(values)
    }

    private fun readValues(): MutableMap<String, String> {
        val file = directory.resolve("values.json")
        if (!Files.exists(file)) return linkedMapOf()
        val parsed = parseJsonOrNull(Files.readString(file)) as? JsonVal.Obj ?: return linkedMapOf()
        return parsed.value.mapNotNull { (key, value) ->
            val text = (value as? JsonVal.Str)?.value ?: return@mapNotNull null
            key to text
        }.toMap(linkedMapOf())
    }

    private fun writeValues(values: Map<String, String>) {
        Files.createDirectories(directory)
        restrictToOwner(directory)
        val json = canonicalJson(jObj(*values.map { it.key to JsonVal.Str(it.value) }.toTypedArray()))
        val file = directory.resolve("values.json")
        writeTextAtomic(file, json)
        restrictToOwner(file)
        if (Files.exists(keyFile())) restrictToOwner(keyFile())
    }

    private fun keyFile(): Path = directory.resolve("key")

    private fun keyBytes(): ByteArray {
        val file = keyFile()
        if (Files.exists(file)) return Files.readAllBytes(file)
        Files.createDirectories(directory)
        restrictToOwner(directory)
        val key = ByteArray(32).also(random::nextBytes)
        val temp = Files.createTempFile(directory, "key", ".tmp")
        try {
            restrictToOwner(temp)
            Files.write(temp, key)
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw error
        }
        restrictToOwner(file)
        return key
    }

    private fun encrypt(nonce: ByteArray, account: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes(), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(account.encodeToByteArray())
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(nonce: ByteArray, account: String, ciphertext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(account.encodeToByteArray())
        cipher.doFinal(ciphertext)
    } catch (_: Exception) {
        null
    }

}

/**
 * Owner-only permissions. POSIX chmod is a no-op on Windows, so a Windows file
 * gets an explicit owner-only ACL with inheritance removed.
 */
internal fun restrictToOwner(path: Path) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
    if (posix != null) {
        val perms = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        if (Files.isDirectory(path)) perms += PosixFilePermission.OWNER_EXECUTE
        posix.setPermissions(perms)
        return
    }
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        ?: error("Couldn't restrict the sync key file to this user.")
    acl.acl = listOf(ownerOnlyAclEntry(acl.owner))
}

/** One allow entry for [owner]. No inherit flags, so the file does not stay world-readable. */
internal fun ownerOnlyAclEntry(owner: UserPrincipal): AclEntry = AclEntry.newBuilder()
    .setType(AclEntryType.ALLOW)
    .setPrincipal(owner)
    .setPermissions(*AclEntryPermission.values())
    .build()

private typealias AtomicMoveNotSupportedException = java.nio.file.AtomicMoveNotSupportedException

internal fun redactSecretArguments(args: List<String>): List<String> {
    val redacted = args.toMutableList()
    val flag = redacted.indexOf("-w")
    if (flag >= 0 && flag + 1 < redacted.size) redacted[flag + 1] = "***"
    return redacted
}

/**
 * macOS login keychain. The passphrase is a `security -i` command on stdin.
 * `ps` shows `security -i` and not the secret. A passphrase that cannot be
 * passed as one quoted command fails so the file store can take it instead.
 */
internal class MacKeychainSecretStore(
    private val run: (List<String>, String?) -> ProcessResult = ::runCommand,
) : SyncSecretStore {
    override val kind: String = "macos-keychain"

    override fun get(account: String): String? {
        val result = run(listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), null)
        if (result.exitCode != 0) return null
        return result.stdout.trimEnd('\n').ifEmpty { null }
    }

    override fun put(account: String, secret: String) {
        if (secret.any { it == '\n' || it == '\r' || it == '\u0000' }) error("Keychain update failed.")
        val command = "add-generic-password -U -s ${securityQuote(SERVICE)} -a ${securityQuote(account)} -w ${securityQuote(secret)}\n"
        val result = run(listOf("/usr/bin/security", "-i"), command)
        if (result.exitCode != 0) error("Keychain update failed.")
    }

    override fun delete(account: String) {
        run(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), null)
    }

    companion object {
        const val SERVICE: String = "Nuvio Analyzed Details Sync"
    }
}

/**
 * Windows Credential Manager. The secret is the process stdin, never an
 * argument. [commandHasSecret] is what a log of the command line would show.
 * If PowerShell or CredWrite is missing, [openSyncSecretStore] is not used
 * on this platform and the encrypted file store is the fallback.
 */
internal class WindowsCredentialSecretStore(
    private val run: (List<String>, String?) -> ProcessResult = ::runCommand,
) : SyncSecretStore {
    override val kind: String = "windows-credential-manager"

    override fun get(account: String): String? {
        val result = run(commandLine(), script("read", target(account), ""))
        if (result.exitCode != 0) return null
        return result.stdout.trimEnd('\n').ifEmpty { null }
    }

    override fun put(account: String, secret: String) {
        val result = run(commandLine(), script("write", target(account), secret))
        if (result.exitCode != 0) error("Credential Manager update failed.")
    }

    override fun delete(account: String) {
        run(commandLine(), script("delete", target(account), ""))
    }

    /** The command line. The secret is only in the process stdin. */
    fun commandHasSecret(secret: String): Boolean = commandLine().any { secret in it }

    fun commandLine(): List<String> = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-WindowStyle",
        "Hidden",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        "-",
    )

    internal fun previewScript(action: String, account: String, secret: String): String = script(action, target(account), secret)

    private fun target(account: String): String {
        require(ACCOUNT.matches(account)) { "Credential account was rejected." }
        return "Nuvio Analyzed Details Sync/$account"
    }

    private fun script(action: String, target: String, secret: String): String {
        require(action == "read" || action == "write" || action == "delete")
        val encoded = SyncCrypto.encodeB64(secret.encodeToByteArray())
        require(Regex("^[A-Za-z0-9+/]*={0,2}$").matches(encoded))
        return SCRIPT
            .replace("__ACTION__", action)
            .replace("__TARGET__", target.replace("'", "''"))
            .replace("__SECRET_B64__", encoded)
    }

    companion object {
        private val ACCOUNT = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,80}$")
        const val SCRIPT: String =
            "\$ErrorActionPreference = 'Stop'\n" +
                "\$action = '__ACTION__'\n" +
                "\$target = '__TARGET__'\n" +
                "\$secret = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__SECRET_B64__'))\n" +
                "Add-Type -TypeDefinition @'\n" +
                "using System; using System.Runtime.InteropServices;\n" +
                "public static class NuvioCred {\n" +
                "  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]\n" +
                "  public struct CREDENTIAL { public uint Flags; public uint Type; public string TargetName; public string Comment; public long LastWritten; public uint CredentialBlobSize; public IntPtr CredentialBlob; public uint Persist; public uint AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName; }\n" +
                "  [DllImport(\"advapi32.dll\", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool CredWrite(ref CREDENTIAL credential, uint flags);\n" +
                "  [DllImport(\"advapi32.dll\", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);\n" +
                "  [DllImport(\"advapi32.dll\", SetLastError = true, CharSet = CharSet.Unicode)] public static extern bool CredDelete(string target, uint type, uint flags);\n" +
                "  [DllImport(\"advapi32.dll\")] public static extern void CredFree(IntPtr credential);\n" +
                "}\n" +
                "'@\n" +
                "if (\$action -eq 'delete') { [NuvioCred]::CredDelete(\$target, 1, 0) | Out-Null; exit 0 }\n" +
                "if (\$action -eq 'read') {\n" +
                "  \$ptr = [IntPtr]::Zero\n" +
                "  if (-not [NuvioCred]::CredRead(\$target, 1, 0, [ref]\$ptr)) { exit 2 }\n" +
                "  try {\n" +
                "    \$cred = [Runtime.InteropServices.Marshal]::PtrToStructure(\$ptr, [type][NuvioCred+CREDENTIAL])\n" +
                "    \$bytes = New-Object byte[] \$cred.CredentialBlobSize\n" +
                "    [Runtime.InteropServices.Marshal]::Copy(\$cred.CredentialBlob, \$bytes, 0, \$bytes.Length)\n" +
                "    [Console]::Out.Write([Text.Encoding]::UTF8.GetString(\$bytes))\n" +
                "  } finally { [NuvioCred]::CredFree(\$ptr) }\n" +
                "  exit 0\n" +
                "}\n" +
                "\$bytes = [Text.Encoding]::UTF8.GetBytes(\$secret)\n" +
                "\$blob = [Runtime.InteropServices.Marshal]::AllocHGlobal(\$bytes.Length)\n" +
                "try {\n" +
                "  [Runtime.InteropServices.Marshal]::Copy(\$bytes, 0, \$blob, \$bytes.Length)\n" +
                "  \$cred = New-Object NuvioCred+CREDENTIAL\n" +
                "  \$cred.Type = 1; \$cred.TargetName = \$target; \$cred.CredentialBlobSize = \$bytes.Length\n" +
                "  \$cred.CredentialBlob = \$blob; \$cred.Persist = 2; \$cred.UserName = 'nuvio'\n" +
                "  if (-not [NuvioCred]::CredWrite([ref]\$cred, 0)) { exit 3 }\n" +
                "} finally { [Runtime.InteropServices.Marshal]::FreeHGlobal(\$blob) }\n"
    }
}

internal data class ProcessResult(val exitCode: Int, val stdout: String)

private fun windowsCredentialManagerAvailable(): Boolean =
    runCatching {
        runCommand(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "exit 0"), null).exitCode == 0
    }.getOrDefault(false)

internal fun runCommand(args: List<String>, stdin: String?): ProcessResult {
    val process = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        if (stdin != null) {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(stdin) }
        } else {
            process.outputStream.close()
        }
        val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ProcessResult(exitCode = -1, stdout = "")
        }
        return ProcessResult(process.exitValue(), stdout)
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Quotes one token for `security -i`. The secret stays off argv. */
internal fun securityQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * Uses the OS store when it accepts the secret, and the encrypted file when
 * the OS store is missing or rejects the write.
 */
internal class FallbackSecretStore(
    private val primary: SyncSecretStore,
    private val fallback: SyncSecretStore,
) : SyncSecretStore {
    private var primaryFailed = false
    override val kind: String
        get() = if (primaryFailed) fallback.kind else primary.kind

    override fun get(account: String): String? {
        if (!primaryFailed) {
            val stored = runCatching { primary.get(account) }.getOrNull()
            if (stored != null) return stored
        }
        return fallback.get(account)
    }

    override fun put(account: String, secret: String) {
        if (!primaryFailed) {
            val saved = runCatching { primary.put(account, secret); true }.getOrDefault(false)
            if (saved) {
                runCatching { fallback.delete(account) }
                return
            }
            primaryFailed = true
        }
        fallback.put(account, secret)
    }

    override fun delete(account: String) {
        runCatching { primary.delete(account) }
        fallback.delete(account)
    }
}

internal fun openSyncSecretStore(fallbackDirectory: Path): SyncSecretStore {
    val file = EncryptedFileSecretStore(fallbackDirectory)
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/security"))) {
        return FallbackSecretStore(MacKeychainSecretStore(), file)
    }
    if (os.contains("win") && windowsCredentialManagerAvailable()) {
        return FallbackSecretStore(WindowsCredentialSecretStore(), file)
    }
    return file
}
