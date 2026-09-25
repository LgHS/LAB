package be.lghs.lab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private const val USER_AGENT = "little-android-brother/0.3 (Android; +https://lghs.be)"

    suspend fun <T> get(url: String, readTimeoutMs: Int = 20_000, read: (InputStream) -> T): T = coroutineScope {
        val conn = URL(url).openConnection() as HttpURLConnection
        // Une lecture bloquante n'est pas interrompue par l'annulation : on coupe la connexion
        val closer = launch { try { awaitCancellation() } finally { conn.disconnect() } }
        try {
            withContext(Dispatchers.IO) {
                conn.connectTimeout = 10_000
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} $url")
                conn.inputStream.use(read) // gzip décompressé de manière transparente
            }
        } finally {
            closer.cancel()
        }
    }

    suspend fun getText(url: String, readTimeoutMs: Int = 20_000): String =
        get(url, readTimeoutMs) { it.readBytes().decodeToString() }
}
