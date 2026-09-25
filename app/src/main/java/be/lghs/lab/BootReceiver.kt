package be.lghs.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Secours si l'app n'est pas le launcher par défaut : l'ouvre à l'allumage de la box.
 * Depuis Android 10, il faut la permission « affichage par-dessus » (voir README).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
