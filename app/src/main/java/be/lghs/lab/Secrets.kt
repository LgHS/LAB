package be.lghs.lab

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

/**
 * Secrets hors de l'APK (le dépôt et les releases GitHub sont publics) : URLs des caméras,
 * clé TEC, jeton Nuki. Déposés une fois sur la box, via [SecretsProvider] :
 *
 *     adb shell content write --uri content://be.lghs.lab.secrets/secrets < secrets.properties
 *
 * Stockés dans le stockage privé de l'app, conservés aux mises à jour. Clé absente du
 * fichier → valeur de local.properties compilée (dev local).
 */
object Secrets {
    internal const val FILE = "secrets.properties"

    @Volatile private var props = Properties()

    val cameraPorte get() = get("camera.porte", BuildConfig.CAMERA_PORTE_URL)
    val cameraSas get() = get("camera.sas", BuildConfig.CAMERA_SAS_URL)
    val tecApiKey get() = get("tec.apiKey", BuildConfig.TEC_API_KEY)
    val nukiToken get() = get("nuki.token", BuildConfig.NUKI_TOKEN)

    private fun get(key: String, fallback: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** Relit le fichier ; appelé à chaque retour au premier plan. */
    fun load(context: Context) {
        val stored = File(context.filesDir, FILE)
        props = Properties().apply {
            if (stored.isFile) stored.inputStream().use { load(it) }
        }
        Log.i("Secrets", if (stored.isFile) "${props.size} secrets chargés" else "Aucun fichier de secrets")
    }
}

/**
 * Dépôt des secrets par adb. Le stockage partagé (/sdcard/Android/data) n'est pas accessible à
 * adb sur toutes les ROM (pas sur la box du LgHS) : on passe par un fournisseur en écriture
 * seule, réservé à l'utilisateur shell d'adb (et root). Aucune lecture possible par ce biais.
 */
class SecretsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("Réservé à adb")
        if ('w' !in mode) throw FileNotFoundException("Écriture seule")
        val file = File(context!!.filesDir, Secrets.FILE)
        Log.i("Secrets", "Fichier de secrets reçu par adb")
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000 // android.os.Process.SHELL_UID (API cachée)
    }
}
