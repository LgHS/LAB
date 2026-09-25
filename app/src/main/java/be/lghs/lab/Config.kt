package be.lghs.lab

import android.net.Uri

data class Camera(val name: String, val url: String) {

    /**
     * Media3 ne supporte ni RTSPS (TLS) ni SRTP. UniFi Protect expose le même flux
     * en RTSP clair sur le port 7447 avec le même alias : on convertit automatiquement.
     */
    fun playableUri(): Uri {
        val uri = Uri.parse(url)
        if (uri.scheme != "rtsps") return uri
        return Uri.Builder()
            .scheme("rtsp")
            .encodedAuthority("${uri.host}:$UNIFI_RTSP_PORT")
            .encodedPath(uri.encodedPath)
            .build()
    }

    private companion object {
        const val UNIFI_RTSP_PORT = 7447
    }
}

object Config {
    // URLs RTSP(S) UniFi hors du dépôt public (elles contiennent le jeton d'accès) : local.properties
    val cameraPorte = Camera("Porte", BuildConfig.CAMERA_PORTE_URL)
    val cameraSas = Camera("SAS Vélo", BuildConfig.CAMERA_SAS_URL)

    const val MOODE_HOST = "172.16.42.253"

    // Bridge Nuki (API HTTP locale) et serrure de la porte d'entrée « HS Rue ».
    // Jeton : local.properties (nuki.token).
    const val NUKI_BRIDGE = "http://172.16.43.219:8080"
    const val NUKI_PORTE_ID = 1167243628L
    const val MPD_PORT = 6600

    // Rue de la Loi, Outremeuse
    const val LATITUDE = 50.6419
    const val LONGITUDE = 5.5860

    /**
     * Arrêts TEC et trajets SNCB : dashboard/transport.json sur GitHub (+ horaires générés par
     * la GitHub Action). Les mêmes fichiers sont embarqués comme valeurs par défaut
     * (dossier dashboard/ du dépôt).
     */
    const val REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/LgHS/LAB/main/dashboard/"
    const val REMOTE_CONFIG_REFRESH_MS = 60 * 60_000L
}
