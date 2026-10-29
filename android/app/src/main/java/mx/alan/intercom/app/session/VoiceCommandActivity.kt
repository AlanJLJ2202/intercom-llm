package mx.alan.intercom.app.session

import android.app.Activity
import android.os.Bundle
import mx.alan.intercom.app.AppGraph

/**
 * Punto de entrada de la Estrategia A (EXP-05).
 *
 * Hipótesis: al mantener el botón de asistente, el EJEAS envía AT+BVRA=1 y
 * Android lanza ACTION_VOICE_COMMAND (o VOICE_SEARCH_HANDS_FREE con pantalla
 * bloqueada) hacia la app elegida. Registramos exactamente qué llegó
 * (acción, extras, referrer) y arrancamos la sesión.
 * Esta actividad es visible por un instante, lo que permite iniciar el
 * servicio de micrófono en primer plano.
 */
class VoiceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        val extras = intent.extras?.keySet()?.associateWith { k -> @Suppress("DEPRECATION") intent.extras?.get(k)?.toString() }
        AppGraph.events.log("trigger", "Intent de voz recibido: ${intent.action}", mapOf(
            "extras" to extras,
            "referrer" to referrer?.toString(),
            "categories" to intent.categories?.toList(),
        ))
        val svc = VoiceSessionService.intent(this, VoiceSessionService.ACTION_START, "intent:${intent.action}")
        startForegroundService(svc)
        finish()
    }
}
