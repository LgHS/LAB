package be.lghs.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Rouvre l'app à l'allumage de la box (secours si elle n'est pas le launcher par défaut) et
 * après une mise à jour automatique, qui l'a arrêtée.
 * Depuis Android 10, il faut la permission « affichage par-dessus » (voir README).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
