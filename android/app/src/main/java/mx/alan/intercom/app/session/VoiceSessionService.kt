package mx.alan.intercom.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import mx.alan.intercom.app.AppGraph
import mx.alan.intercom.app.audio.BluetoothAudioRouter
import mx.alan.intercom.app.audio.SpeechOutput
import mx.alan.intercom.app.bt.BluetoothEventMonitor
import mx.alan.intercom.app.bt.HeadsetProfile
import mx.alan.intercom.app.ui.MainActivity
import java.util.concurrent.Executors

/**
 * Servicio en primer plano "Modo ruta".
 *
 * Se ARMA desde la interfaz (app visible), lo que le otorga el permiso de
 * micrófono "while-in-use" durante toda su vida; así, disparadores que
 * llegan con la app en segundo plano (comando de voz HFP, botón multimedia)
 * pueden abrir una sesión sin violar las restricciones de Android 14+.
 * Armado no significa escuchando: el micrófono solo se abre dentro de una sesión.
 */
class VoiceSessionService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var headset: HeadsetProfile
    private lateinit var router: BluetoothAudioRouter
    private lateinit var speech: SpeechOutput
    private lateinit var engine: SessionEngine
    private lateinit var monitor: BluetoothEventMonitor
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val log = AppGraph.events
        headset = HeadsetProfile(this)
        router = BluetoothAudioRouter(this, headset, log)
        speech = SpeechOutput(this, log)
        engine = SessionEngine(this, router, speech, log)
        monitor = BluetoothEventMonitor(this, log)
        router.onFocusLost = { transient -> engine.cancel(if (transient) "foco perdido (transitorio: llamada/navegación)" else "foco perdido") }
        monitor.onHfpDisconnected = { engine.cancel("casco desconectado") }
        monitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_ARM -> AppGraph.events.log("service", "Modo ruta armado")
            ACTION_START -> startSession(intent.getStringExtra(EXTRA_TRIGGER) ?: "desconocido")
            ACTION_STOP -> engine.cancel("detener desde notificación/UI")
            ACTION_TOGGLE -> if (engine.active) engine.cancel("alternar") else startSession(intent.getStringExtra(EXTRA_TRIGGER) ?: "alternar")
            ACTION_DISARM -> { engine.cancel("desarmar"); stopSelf() }
        }
        if (AppGraph.settings.mediaButtonTrigger) ensureMediaSession() else releaseMediaSession()
        return START_NOT_STICKY
    }

    private fun startSession(trigger: String) {
        if (engine.active) {
            AppGraph.events.log("service", "Sesión ya activa; disparador ignorado: $trigger")
            return
        }
        if (router.isInCall()) {
            AppGraph.events.log("service", "En llamada: sesión no iniciada ($trigger)")
            return
        }
        worker.execute { engine.run(trigger) }
    }

    /**
     * Estrategia A-bis (EXP-05): con una MediaSession activa, los botones
     * AVRCP del casco (play/pausa, siguiente) llegan aquí. Para recibirlos,
     * Android exige que esta app haya reproducido audio recientemente; se
     * reproduce 1 s de silencio al armar.
     */
    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val s = MediaSession(this, "intercom-ptt")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = if (Build.VERSION.SDK_INT >= 33) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                if (ev != null) {
                    AppGraph.events.log("button", "Botón multimedia: ${KeyEvent.keyCodeToString(ev.keyCode)} action=${ev.action} repeat=${ev.repeatCount} longPress=${ev.isLongPress}")
                    if (ev.action == KeyEvent.ACTION_UP && ev.keyCode in PTT_KEYS) {
                        if (engine.active) engine.cancel("botón") else startSession("media:${KeyEvent.keyCodeToString(ev.keyCode)}")
                    }
                }
                return true
            }
        })
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        s.isActive = true
        mediaSession = s
        playSilence()
        AppGraph.events.log("button", "MediaSession activa: botones multimedia del casco → sesión de voz")
    }

    private fun releaseMediaSession() {
        mediaSession?.let { it.isActive = false; it.release() }
        mediaSession = null
    }

    private fun playSilence() {
        worker.execute {
            val rate = 16_000
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(rate * 2)
                .build()
            track.write(ShortArray(rate), 0, rate)
            track.play()
            Thread.sleep(1100)
            track.release()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Modo ruta", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val disarm = PendingIntent.getService(this, 2, intent(this, ACTION_DISARM), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Intercom IA · modo ${AppGraph.mode().name.lowercase()}")
            .setContentText(if (engine.active) "Sesión de voz activa" else "Armado: esperando botón o comando")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .addAction(Notification.Action.Builder(null, "Desarmar", disarm).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        engine.cancel("servicio destruido")
        releaseMediaSession()
        monitor.stop()
        speech.shutdown()
        headset.close()
        worker.shutdownNow()
        AppGraph.events.log("service", "Modo ruta desarmado")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ARM = "mx.alan.intercom.ARM"
        const val ACTION_START = "mx.alan.intercom.START"
        const val ACTION_STOP = "mx.alan.intercom.STOP"
        const val ACTION_TOGGLE = "mx.alan.intercom.TOGGLE"
        const val ACTION_DISARM = "mx.alan.intercom.DISARM"
        const val EXTRA_TRIGGER = "trigger"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 7
        private val PTT_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)

        fun intent(ctx: Context, action: String, trigger: String? = null) =
            Intent(ctx, VoiceSessionService::class.java).setAction(action).apply { trigger?.let { putExtra(EXTRA_TRIGGER, it) } }
    }
}
