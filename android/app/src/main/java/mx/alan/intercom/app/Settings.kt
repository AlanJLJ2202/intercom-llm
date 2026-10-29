package mx.alan.intercom.app

import android.content.Context
import mx.alan.intercom.core.llm.AnthropicChatModel
import mx.alan.intercom.core.policy.RideMode
import mx.alan.intercom.core.tools.Scopes

enum class SttEngine { SYSTEM, CLOUD }
enum class OutputRoute { SCO, A2DP }
enum class RouteStrategy { COMMUNICATION_DEVICE, VOICE_RECOGNITION, BOTH }

/**
 * Configuración persistente. Las claves de API se guardan en el almacenamiento
 * privado de la app (no exportable, allowBackup=false). Para producción, ver
 * docs/09-riesgos.md: migrar a Android Keystore o a un backend que firme peticiones.
 */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("intercom", Context.MODE_PRIVATE)

    var anthropicKey: String
        get() = p.getString("anthropic_key", "") ?: ""
        set(v) = p.edit().putString("anthropic_key", v.trim()).apply()

    var anthropicModel: String
        get() = p.getString("anthropic_model", AnthropicChatModel.DEFAULT_MODEL) ?: AnthropicChatModel.DEFAULT_MODEL
        set(v) = p.edit().putString("anthropic_model", v.trim()).apply()

    var openAiKey: String
        get() = p.getString("openai_key", "") ?: ""
        set(v) = p.edit().putString("openai_key", v.trim()).apply()

    var controlUrl: String
        get() = p.getString("control_url", "") ?: ""
        set(v) = p.edit().putString("control_url", v.trim()).apply()

    var controlToken: String
        get() = p.getString("control_token", "") ?: ""
        set(v) = p.edit().putString("control_token", v.trim()).apply()

    /** El modo es manual: la app nunca baja a STOPPED por su cuenta. */
    var mode: RideMode
        get() = RideMode.valueOf(p.getString("mode", RideMode.STOPPED.name)!!)
        set(v) = p.edit().putString("mode", v.name).apply()

    var sttEngine: SttEngine
        get() = SttEngine.valueOf(p.getString("stt", SttEngine.SYSTEM.name)!!)
        set(v) = p.edit().putString("stt", v.name).apply()

    var outputRoute: OutputRoute
        get() = OutputRoute.valueOf(p.getString("output", OutputRoute.SCO.name)!!)
        set(v) = p.edit().putString("output", v.name).apply()

    var routeStrategy: RouteStrategy
        get() = RouteStrategy.valueOf(p.getString("route_strategy", RouteStrategy.COMMUNICATION_DEVICE.name)!!)
        set(v) = p.edit().putString("route_strategy", v.name).apply()

    var useLocalFastPath: Boolean
        get() = p.getBoolean("fast_path", true)
        set(v) = p.edit().putBoolean("fast_path", v).apply()

    /** Usar botones multimedia (AVRCP) como pulsar-para-hablar. Desactivado por defecto. */
    var mediaButtonTrigger: Boolean
        get() = p.getBoolean("media_trigger", false)
        set(v) = p.edit().putBoolean("media_trigger", v).apply()

    var grantedScopes: Set<String>
        get() = p.getStringSet("scopes", Scopes.DEFAULT) ?: Scopes.DEFAULT
        set(v) = p.edit().putStringSet("scopes", v).apply()

    /** Máximo de turnos por sesión (se reduce en conducción). */
    fun maxTurns(): Int = if (mode == RideMode.DRIVING) 3 else 8
}
