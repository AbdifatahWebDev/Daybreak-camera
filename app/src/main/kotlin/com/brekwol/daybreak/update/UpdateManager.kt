package com.brekwol.daybreak.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.brekwol.daybreak.BuildConfig
import com.brekwol.daybreak.data.SettingsRepository
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A version published on GitHub Releases. */
data class Release(
    val version: Version,
    val pageUrl: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val checksumsUrl: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    /** Found, not downloaded yet (automatic downloads wait for Wi-Fi). */
    data class Available(val release: Release) : UpdateState
    /** [progress] is 0..1, or -1 when the size is unknown. */
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Installing(val release: Release) : UpdateState
    /** [reinstall] means the update can't replace this copy (different signing key). */
    data class Failed(val message: String, val release: Release?, val reinstall: Boolean = false) : UpdateState
}

enum class InstallStart { Started, NeedsPermission, NothingToInstall }

/**
 * Keeps the GitHub build of Daybreak up to date from GitHub Releases.
 *
 * Once a day (when the app starts) it asks the GitHub API for the newest release,
 * downloads its APK in the background on Wi-Fi, checks the SHA-256 published with
 * the release and that the APK is signed with the same key as this app, then
 * installs it with PackageInstaller when the user taps Install.
 *
 * Store builds (and debug builds) leave it switched off: stores update apps themselves.
 */
class UpdateManager(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext

    val enabled: Boolean = BuildConfig.UPDATER && !BuildConfig.DEBUG && BuildConfig.UPDATE_REPO.isNotBlank()
    val currentVersion: String = BuildConfig.VERSION_NAME
    private val current: Version = Version.parse(currentVersion) ?: Version(0, 0, 0)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-line results of a "Check now" tap, shown as a toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val dir = File(this.context.cacheDir, "updates")
    private val busy = Mutex()

    /** Called once per app start. Checks at most once a day, if the user allows it. */
    fun onAppStart() {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            removeOldDownloads()
            val prefs = settings.current()
            val due = System.currentTimeMillis() - prefs.lastUpdateCheck >= CHECK_INTERVAL_MS
            if (prefs.updateAutoCheck && due) checkAndMaybeDownload(manual = false)
        }
    }

    /** The "Check now" button. A new version downloads right away, on any network. */
    fun checkNow() {
        if (!enabled) return
        scope.launch(Dispatchers.IO) { checkAndMaybeDownload(manual = true) }
    }

    /** Downloads the update that an automatic check found but left for later. */
    fun download() {
        val available = _state.value as? UpdateState.Available ?: return
        scope.launch(Dispatchers.IO) { busy.withLock { downloadLocked(available.release) } }
    }

    /**
     * Installs a downloaded update. Android shows its own confirmation unless it lets
     * Daybreak update itself silently. Daybreak closes while it is replaced.
     */
    fun install(): InstallStart {
        val ready = _state.value as? UpdateState.Ready ?: return InstallStart.NothingToInstall
        if (!context.packageManager.canRequestPackageInstalls()) return InstallStart.NeedsPermission
        lastReady = ready
        _state.value = UpdateState.Installing(ready.release)
        scope.launch(Dispatchers.IO) {
            try {
                commitInstall(ready.apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = UpdateState.Failed(describe(error), ready.release)
            }
        }
        return InstallStart.Started
    }

    private var lastReady: UpdateState.Ready? = null

    /** Result of an install, reported by [UpdateInstallReceiver]. */
    fun onInstallStatus(status: Int, message: String?) {
        val ready = lastReady
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value
            // The user closed the Android dialog: offer the same update again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> ready ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            -> UpdateState.Failed(REINSTALL_MESSAGE, ready?.release, reinstall = true)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                UpdateState.Failed("Not enough free space to install the update.", ready?.release)
            else -> UpdateState.Failed(
                message?.takeIf { it.isNotBlank() } ?: "Android didn't install the update.",
                ready?.release,
            )
        }
    }

    private suspend fun checkAndMaybeDownload(manual: Boolean) = busy.withLock {
        val now = _state.value
        if (now is UpdateState.Downloading || now is UpdateState.Installing) return@withLock
        _state.value = UpdateState.Checking
        val started = SystemClock.elapsedRealtime()
        val prefs = settings.current()
        val newest = try {
            findNewestRelease(includePreReleases = prefs.updatePreReleases || current.isPreRelease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (manual) holdChecking(started)
            _state.value = UpdateState.Failed(describe(error), null)
            if (manual) _messages.tryEmit("Couldn't check for updates. ${describe(error)}")
            return@withLock
        }
        val checkedAt = System.currentTimeMillis()
        settings.setLastUpdateCheck(checkedAt)
        if (manual) holdChecking(started)
        if (newest == null || newest.version <= current) {
            _state.value = UpdateState.UpToDate(checkedAt)
            if (manual) _messages.tryEmit("You're up to date. Daybreak $currentVersion is the latest version.")
            return@withLock
        }
        _state.value = UpdateState.Available(newest)
        if (manual) _messages.tryEmit("Daybreak ${newest.version} is out. Downloading it now.")
        if (manual || (prefs.updateAutoDownload && isOnUnmeteredNetwork())) downloadLocked(newest)
    }

    /** Keeps "Checking…" on screen long enough to see after a tap. */
    private suspend fun holdChecking(started: Long) {
        val remaining = MIN_CHECKING_MS - (SystemClock.elapsedRealtime() - started)
        if (remaining > 0) delay(remaining)
    }

    private fun findNewestRelease(includePreReleases: Boolean): Release? {
        val json = JSONArray(fetchText("$API/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=20", GITHUB_JSON))
        var best: Release? = null
        for (i in 0 until json.length()) {
            val release = json.optJSONObject(i)?.let { parseRelease(it, includePreReleases) } ?: continue
            if (best == null || release.version > best.version) best = release
        }
        return best
    }

    private fun parseRelease(json: JSONObject, includePreReleases: Boolean): Release? {
        if (json.optBoolean("draft")) return null
        if (json.optBoolean("prerelease") && !includePreReleases) return null
        val version = Version.parse(json.optString("tag_name")) ?: return null
        if (version.isPreRelease && !includePreReleases) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var checksums: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (apk == null && name.startsWith("daybreak-") && name.endsWith(".apk")) apk = asset
            if (name == "SHA256SUMS.txt") checksums = asset
        }
        val apkAsset = apk ?: return null
        return Release(
            version = version,
            pageUrl = json.optString("html_url"),
            apkUrl = apkAsset.optString("browser_download_url"),
            apkName = apkAsset.optString("name"),
            apkSize = apkAsset.optLong("size"),
            checksumsUrl = checksums?.optString("browser_download_url"),
        )
    }

    private suspend fun downloadLocked(release: Release) {
        dir.mkdirs()
        val target = File(dir, "daybreak-${release.version}.apk")
        try {
            if (!(target.exists() && target.length() == release.apkSize)) {
                val part = File(dir, target.name + ".part")
                part.delete()
                _state.value = UpdateState.Downloading(release, 0f)
                val connection = open(release.apkUrl, accept = null)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("The download failed (GitHub answered $code).")
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= PROGRESS_STEP_BYTES) {
                                    reported = done
                                    val progress = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else -1f
                                    _state.value = UpdateState.Downloading(release, progress)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                target.delete()
                if (!part.renameTo(target)) throw IOException("Could not save the update.")
            }
            verify(release, target)
            _state.value = UpdateState.Ready(release, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (mismatch: SignerMismatchException) {
            target.delete()
            _state.value = UpdateState.Failed(REINSTALL_MESSAGE, release, reinstall = true)
        } catch (error: Exception) {
            target.delete()
            _state.value = UpdateState.Failed(describe(error), release)
        }
    }

    /** Checks the published SHA-256, the package name and the signing key. */
    private fun verify(release: Release, apk: File) {
        release.checksumsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val expected = fetchText(url, accept = null).lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it.last().removePrefix("*").removePrefix("./") == release.apkName }
                ?.first()
            if (expected != null && !expected.equals(sha256(apk), ignoreCase = true)) {
                throw IOException("The download was damaged. Try again.")
            }
        }
        val packages = context.packageManager
        val archive = packages.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException("The downloaded file isn't a valid app.")
        if (archive.packageName != context.packageName) throw IOException("The downloaded file isn't Daybreak.")
        val installed = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val ours = signerDigests(installed.signingInfo)
        val theirs = signerDigests(archive.signingInfo)
        // Only refuse when both keys are readable and differ; Android checks again when installing.
        if (ours.isNotEmpty() && theirs.isNotEmpty() && ours.intersect(theirs).isEmpty()) {
            throw SignerMismatchException()
        }
    }

    private fun commitInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets later updates skip the dialog once Daybreak installed itself.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("daybreak.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                    .setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun removeOldDownloads() {
        dir.listFiles()?.forEach { file ->
            val version = Version.parse(file.name.removePrefix("daybreak-").substringBefore(".apk"))
            if (file.name.endsWith(".part") || version == null || version <= current) file.delete()
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun open(url: String, accept: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Daybreak/$currentVersion (Android)")
            if (accept != null) setRequestProperty("Accept", accept)
        }

    private fun fetchText(url: String, accept: String?): String {
        val connection = open(url, accept)
        try {
            when (val code = connection.responseCode) {
                in 200..299 -> return connection.inputStream.bufferedReader().use { it.readText() }
                403, 429 -> throw IOException("GitHub is busy. Try again in an hour.")
                404 -> throw IOException("No releases found on GitHub.")
                else -> throw IOException("GitHub answered $code. Try again later.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is UnknownHostException, is ConnectException -> "No internet connection."
        is SocketTimeoutException -> "The connection timed out. Try again."
        is IOException -> error.message ?: "The network request failed."
        is org.json.JSONException -> "GitHub sent an unexpected answer."
        else -> error.message ?: "Something went wrong."
    }

    private class SignerMismatchException : IOException("Signed with a different key")

    companion object {
        private const val API = "https://api.github.com"
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val CHECK_INTERVAL_MS = 20L * 60 * 60 * 1000
        private const val PROGRESS_STEP_BYTES = 256L * 1024
        private const val MIN_CHECKING_MS = 900L

        const val REINSTALL_MESSAGE =
            "This version is signed with a different key, so it can't replace the Daybreak you have. " +
                "Uninstall Daybreak, then install the new version from GitHub. " +
                "Photos you took stay in DCIM/Daybreak."

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun signerDigests(info: SigningInfo?): Set<String> {
            if (info == null) return emptySet()
            val certificates = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            val digest = MessageDigest.getInstance("SHA-256")
            return certificates.orEmpty().map { signature ->
                digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
    }
}
