package be.lghs.lab.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

/** Sons courts pour attirer l'œil vers l'écran : livreur proche, porte restée ouverte (res/raw). */
class Chime(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(sound: Int) {
        player?.release()
        // Canal média : celui qui sort sur le HDMI au volume réglé sur la box
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = context.getSystemService(AudioManager::class.java)
        player = MediaPlayer.create(context, sound, attributes, audio.generateAudioSessionId())?.apply {
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            start()
        }
        if (player == null) Log.w("Chime", "lecture du son impossible")
    }

    fun release() {
        player?.release()
        player = null
    }
}
