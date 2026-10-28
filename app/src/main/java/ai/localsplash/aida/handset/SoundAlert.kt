package ai.localsplash.aida.handset

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SoundAlert {
    private const val TAG = "SoundAlert"

    /**
     * Plays a loud chime once to signal urgent human escalation.
     * Uses ToneGenerator on STREAM_ALARM combined with system ringtone/notification alert.
     */
    fun playChime(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 1. Play ToneGenerator chime on alarm stream at full volume
                val toneGen = try {
                    ToneGenerator(AudioManager.STREAM_ALARM, 100)
                } catch (e: Exception) {
                    try {
                        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    } catch (_: Exception) {
                        null
                    }
                }

                if (toneGen != null) {
                    try {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                        delay(400)
                        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 450)
                        delay(500)
                    } finally {
                        toneGen.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator chime failed: ${e.message}")
            }

            try {
                // 2. Also trigger standard notification / alarm ringtone as supplementary chime
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (alertUri != null) {
                    val ringtone = RingtoneManager.getRingtone(context.applicationContext, alertUri)
                    if (ringtone != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ringtone.audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        }
                        ringtone.play()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ringtone chime failed: ${e.message}")
            }
        }
    }
}
