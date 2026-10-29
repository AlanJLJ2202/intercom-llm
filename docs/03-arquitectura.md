# 3. Arquitectura del sistema

## 3.1 Decisión de plataforma

El criterio fue **control real sobre Bluetooth, audio, eventos y segundo plano**, no familiaridad.

| Necesidad | Android nativo (Kotlin) | iOS nativo (Swift) | Flutter |
|---|---|---|---|
| Abrir HFP/SCO y elegir el mic del casco | `setCommunicationDevice`, `BluetoothHeadset.startVoiceRecognition` | `AVAudioSession(.playAndRecord, .allowBluetooth)` — sí, sin elegir estrategia | Solo vía plugins/canales nativos |
| Recibir el botón "asistente" (AT+BVRA) | Intent `VOICE_COMMAND` hacia nuestra app (hipótesis EXP-05) | **No**: siempre va a Siri. Solo App Intents/Atajos ("Oye Siri, …") | Depende del nativo |
| Botones AVRCP | `MediaSession` | `MPRemoteCommandCenter` (solo como app "Now Playing") | Plugins |
| Comandos AT de fabricante, eventos SCO, UUIDs SDP | Sí (APIs públicas) | No | No |
| BLE GATT | Sí | Sí (CoreBluetooth) | Plugins |
| Micrófono en segundo plano | Servicio en primer plano tipo `microphone` | Modo de fondo `audio` | Nativo |

**Decisión**: prototipo **Android nativo en Kotlin**, con la lógica (orquestador, políticas,
herramientas, proveedores, VAD) en un **módulo JVM puro** (`android/core`) que se prueba sin
emulador y es reutilizable desde un backend. Flutter no aporta aquí: todo lo crítico sería código
nativo detrás de canales. iOS queda como adaptación posterior (§3.5).

## 3.2 Diagrama

```
 ┌──────────────────────┐   HFP/SCO (voz 8/16 kHz)    ┌──────────────────────────────────────────────┐
 │   EJEAS V6 Pro       │ ◄─────────────────────────► │  TELÉFONO ANDROID                            │
 │  mic + altavoces     │   A2DP (música/TTS opc.)    │                                              │
 │  botones (BVRA/AVRCP)│ ──── AT+BVRA / AVRCP ─────► │  VoiceCommandActivity / MediaSession         │
 └──────────────────────┘   BLE (¿SafeRiding?)        │            │ (disparador)                    │
                                                      │            ▼                                 │
                                                      │  VoiceSessionService (modo ruta, primer plano)│
                                                      │   ├─ BluetoothAudioRouter  (ruta SCO, foco)  │
                                                      │   ├─ Captura: SpeechRecognizer | AudioRecord │
                                                      │   │            + EnergyVad (core)            │
                                                      │   ├─ STT: sistema (local) | nube             │
                                                      │   ├─ Orchestrator (core) ◄── InteractionPolicy (modo manual)
                                                      │   │    ├─ LocalIntentModel (fast path, offline)
                                                      │   │    ├─ ChatModel ──► Anthropic (Claude) [intercambiable]
                                                      │   │    └─ ToolRegistry (scopes, riesgo, confirmación, aplazado)
                                                      │   │          └─ ControlClient ─(HTTPS, Bearer)─┐
                                                      │   └─ SpeechOutput (TTS local, earcons)      │  │
                                                      └─────────────────────────────────────────────┘  │
                                                                                                       ▼
                                                      ┌──────────────────────────────────────────────┐
                                                      │ control-bridge (Node) ── webhook firmado ──► CONTROL
                                                      │ tokens con scopes · validación · bandeja      │
                                                      └──────────────────────────────────────────────┘
```

## 3.3 Separación de responsabilidades

| Capa | Módulo | Interfaz intercambiable |
|---|---|---|
| Captura de audio | `app/audio/PcmCapture`, `SystemRecognizer` | estrategia de ruta (`RouteStrategy`) |
| Procesamiento de voz | `core/audio/EnergyVad`, `UtteranceCollector` | `VoiceActivityDetector` |
| Transcripción | `core/stt` | `SpeechToText` |
| Modelo conversacional | `core/llm` | `ChatModel` (Anthropic implementado; OpenAI/Gemini/local por implementar) |
| Orquestación | `core/orchestrator` | — |
| Herramientas | `core/tools` | `VoiceTool` + scopes |
| Integración Control | `core/control` | `ControlClient` (HTTP, memoria, bandeja offline) |
| Síntesis | `app/audio/SpeechOutput` | (TTS del sistema; nube opcional futura) |

### Multiproveedor
`ChatModel` recibe historial neutral (`ChatMessage`) y herramientas en JSON Schema (`ToolSpec`).
`AnthropicChatModel` usa el SDK oficial de Java y conserva el contenido del asistente sin editarlo
(`Message.toParam()`). Para añadir OpenAI/Gemini/local basta otra implementación de `ChatModel`
(la política, las herramientas y la voz no cambian). Modelo por defecto: `claude-opus-5-5` con
`effort: low` (latencia) y *fallbacks* del lado del servidor ante `refusal`; configurable en la app.

### Historial
El historial **nunca se edita**: si cambia el modo o crece demasiado, se reinicia la conversación.
Las sesiones de voz son cortas; esto evita invalidar bloques de razonamiento y mantiene la caché estable.

## 3.4 Activación (tres estrategias)

| Estrategia | Mecanismo | Estado |
|---|---|---|
| A. Botón del casco | (1) AT+BVRA → intent `VOICE_COMMAND`/`VOICE_SEARCH_HANDS_FREE`/`ASSIST` → `VoiceCommandActivity`; (2) botones AVRCP → `MediaSession` (opt-in) | Implementado; **hipótesis** hasta EXP-05 |
| B. Palabra de activación | No implementada a propósito: exige mantener SCO abierto (mic del casco) de forma continua — degrada la música a calidad de llamada y consume batería del casco. Ver backlog | Evaluada, aplazada |
| C. Desde el teléfono | Botón "Iniciar sesión" en la app (detenido) y notificación con "Detener" | Implementado |

**Modo ruta**: el servicio se arma desde la app visible (obtiene permiso de micrófono "while-in-use"
durante su vida), así los disparadores que llegan con la app en segundo plano pueden abrir sesión sin
chocar con las restricciones de Android 14+. Armado ≠ escuchando.

## 3.5 Adaptación a iOS

- Audio: `AVAudioSession` categoría `.playAndRecord`, modo `.voiceChat`, opción `.allowBluetooth` (HFP);
  `.allowBluetoothA2DP` para salida de alta calidad sin micrófono.
- Activación: **no** se puede capturar el botón de asistente (va a Siri). Alternativa: App Intents +
  App Shortcuts: "Oye Siri, Intercom IA" abre la sesión; o `MPRemoteCommandCenter` si la app es "Now Playing".
- Segundo plano: modo `audio`; las sesiones deben iniciarse desde un intent/atajo.
- Núcleo: reescribir `core` en Swift o exponerlo vía un backend (el orquestador es portable a servidor JVM).
- Conclusión: viable con activación por Siri, menos control experimental que Android.

## 3.6 ¿Y un backend propio?
No es obligatorio para el MVP: el teléfono llama a Claude y a Control directamente. Un backend
(Node/TypeScript o el mismo `core` en JVM) tendría sentido para: no guardar API keys en el teléfono,
centralizar auditoría de herramientas, y ejecutar tareas largas aplazadas. El `control-bridge` ya es
el embrión de esa capa para la parte de Control.
