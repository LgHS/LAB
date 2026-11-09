package be.lghs.lab.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import be.lghs.lab.BuildConfig
import be.lghs.lab.Config
import be.lghs.lab.data.Http
import be.lghs.lab.data.HttpStatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Mise à jour automatique depuis les releases GitHub (tag vX.Y.Z publié par android.yml).
 *
 * Prérequis sur la box, une fois : `adb shell appops set be.lghs.lab REQUEST_INSTALL_PACKAGES allow`.
 * Installation sans confirmation à partir d'Android 12 si l'app est son propre installeur
 * (donc après la première mise à jour) ; sinon Android affiche une confirmation à l'écran.
 */
object Updater {
    private const val TAG = "Updater"
    private const val API = "https://api.github.com/repos/${Config.UPDATE_REPO}/releases/latest"

    /** Release déjà refusée (signature, paquet…) : on ne la retélécharge pas à chaque vérification. */
    private var rejectedTag: String? = null

    /** Installe la dernière release si elle est plus récente. true si une installation a été lancée. */
    suspend fun checkAndInstall(context: Context): Boolean {
        // Build debug (clé debug) ou preview (autre paquet) : une release ne pourrait pas l'écraser
        if (BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".preview")) return false
        val latest = try {
            Http.getText(API)
        } catch (e: HttpStatusException) {
            if (e.code == 404) return false // aucune release publiée : rien à faire, pas d'alerte
            throw e
        }
        val release = Release.parse(JSONObject(latest)) ?: return false
        if (release.versionCode <= BuildConfig.VERSION_CODE || release.tag == rejectedTag) return false
        if (!canInstall(context)) {
            Log.w(TAG, "${release.tag} disponible, mais installation non autorisée (appops REQUEST_INSTALL_PACKAGES)")
            return false
        }

        Log.i(TAG, "Téléchargement de ${release.tag}")
        val apk = File(context.cacheDir, "update.apk")
        try {
            Http.get(release.apkUrl, readTimeoutMs = 60_000, accept = "application/octet-stream") { input ->
                apk.outputStream().use { input.copyTo(it) }
            }
            withContext(Dispatchers.IO) {
                try {
                    verify(context, apk, release)
                } catch (e: IOException) {
                    rejectedTag = release.tag
                    throw e
                }
                install(context, apk)
            }
            return true
        } finally {
            apk.delete()
        }
    }

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /**
     * Android refuserait de toute façon un APK d'un autre paquet ou d'une autre clé : on le vérifie
     * avant, pour ne pas afficher une confirmation vouée à l'échec et pour un message clair.
     */
    private fun verify(context: Context, apk: File, release: Release) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, signaturesFlag())
            ?: throw IOException("${release.tag} : APK illisible")
        if (archive.packageName != context.packageName) {
            throw IOException("${release.tag} : paquet ${archive.packageName} au lieu de ${context.packageName}")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= BuildConfig.VERSION_CODE) {
            throw IOException("${release.tag} : versionCode ${PackageInfoCompat.getLongVersionCode(archive)} pas plus récent")
        }
        val installed = pm.getPackageInfo(context.packageName, signaturesFlag())
        if (signatures(archive) != signatures(installed)) {
            throw IOException("${release.tag} : signé avec une autre clé que l'app installée")
        }
    }

    // GET_SIGNATURES : seul flag fiable sur un APK non installé quelle que soit la version
    // d'Android (GET_SIGNING_CERTIFICATES renvoie null pour une archive sur certaines versions).
    @Suppress("DEPRECATION")
    private fun signaturesFlag() = PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        info.signatures.orEmpty().map { it.toCharsString() }.toSet()

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable : le système y ajoute le résultat (statut, demande de confirmation)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java), flags,
            )
            session.commit(result.intentSender)
        }
        Log.i(TAG, "Installation lancée (session $sessionId)")
    }
}

/** Résultat de l'installation ; affiche la confirmation Android si elle est exigée. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "Mise à jour installée")
            else -> Log.w("Updater", "Échec : ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
