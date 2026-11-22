package dev.sharptalons.obsdirector.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.sharptalons.obsdirector.BuildConfig
import dev.sharptalons.obsdirector.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from this project's GitHub Releases. The newest published release is compared
 * with the installed version (semantic versioning); its APK is downloaded with the system download
 * manager and handed to the Android installer. Updates install only when signed with the same key.
 */
object Updater {
    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sizeBytes: Long, val pageUrl: String)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val checkedAt: Long) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val id: Long) : State()
        data class ReadyToInstall(val release: Release, val file: File) : State()
        data class Failed(val message: String) : State()
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val status: StateFlow<State> get() = state

    val currentVersion: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")

    /** Compares "1.2.3" style versions; a pre-release (1.2.3-beta1) sorts before its release. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String): Pair<List<Int>, String> {
            val clean = v.trim().removePrefix("v")
            val core = clean.substringBefore('-')
            val pre = if (clean.contains('-')) clean.substringAfter('-') else ""
            return core.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(3 - minOf(3, it.size)) { 0 } } to pre
        }
        val (a, ap) = parts(candidate)
        val (b, bp) = parts(current)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        if (ap.isEmpty() && bp.isNotEmpty()) return true
        if (ap.isNotEmpty() && bp.isEmpty()) return false
        return ap > bp
    }

    suspend fun check(context: Context, quiet: Boolean = false): State = withContext(Dispatchers.IO) {
        if (!quiet) state.value = State.Checking
        val result = runCatching {
            val conn = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest").openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OBS-Director-Android/${currentVersion}")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            when (conn.responseCode) {
                404 -> return@runCatching State.UpToDate(System.currentTimeMillis())
                200 -> {}
                else -> return@runCatching State.Failed("GitHub answered ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = Json.parseToJsonElement(body).jsonObject
            if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@runCatching State.UpToDate(System.currentTimeMillis())
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@runCatching State.Failed("No version on the release")
            val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull {
                val n = it["name"]?.jsonPrimitive?.content ?: ""
                n.endsWith(".apk") && n.contains("android")
            }
            if (!isNewer(tag, currentVersion) || asset == null) State.UpToDate(System.currentTimeMillis())
            else State.Available(
                Release(
                    version = tag.removePrefix("v"),
                    notes = o["body"]?.jsonPrimitive?.content?.take(4000) ?: "",
                    apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
                    apkName = asset["name"]!!.jsonPrimitive.content,
                    sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                    pageUrl = o["html_url"]?.jsonPrimitive?.content ?: "",
                )
            )
        }.getOrElse { State.Failed(it.message ?: "Couldn't reach GitHub") }
        Prefs.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        if (!(quiet && result is State.Failed)) state.value = result
        result
    }

    fun download(context: Context, release: Release) {
        val dm = context.getSystemService(DownloadManager::class.java)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        File(dir, release.apkName).delete()
        val req = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("OBS Director ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, release.apkName)
        val id = dm.enqueue(req)
        state.value = State.Downloading(release, id)
    }

    internal fun onDownloadComplete(context: Context, id: Long) {
        val s = state.value as? State.Downloading ?: return
        if (s.id != id) return
        val dm = context.getSystemService(DownloadManager::class.java)
        val ok = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), s.release.apkName)
        state.value = if (ok && file.exists()) State.ReadyToInstall(s.release, file) else State.Failed("The download didn't finish")
        if (ok && file.exists()) install(context, file)
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, file: File) {
        if (!canInstall(context)) { openInstallPermission(context); return }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            Updater.onDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
        }
    }
}
