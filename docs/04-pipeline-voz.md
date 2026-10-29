# 4. Pipeline de voz

## 4.1 Etapas implementadas

| # | Etapa | Implementación | Marca de latencia |
|---|---|---|---|
| 1 | Activación explícita | botón (BVRA/AVRCP), app, notificación | `activation` |
| 2 | Ruta de voz al casco | `BluetoothAudioRouter.acquire` (SCO) + foco de audio | `route_ready` |
| 3 | Tono "te escucho" | `SpeechOutput.earcon` (120 ms, volumen moderado) | `capture_start` |
| 4 | Captura + VAD | `SpeechRecognizer` (sistema) **o** `AudioRecord` + `EnergyVad` + `UtteranceCollector` | `speech_start`, `speech_end` |
| 5 | Transcripción | en el dispositivo (sistema) **o** nube (`OpenAiTranscriber`) | `stt_final` |
| 6 | Intención | `LocalIntentModel` (fast path, sin red) → si no aplica, `ChatModel` | `llm_first` |
| 7 | Herramientas | `ToolRegistry` (scopes, riesgo, confirmación, aplazamiento) | `tool_<nombre>` |
| 8 | Respuesta | `InteractionPolicy.shapeSpoken` (modo conducción: ≤2 frases, sin markdown) | `reply_ready` |
| 9 | Síntesis | TTS local; salida por SCO (misma ruta) o A2DP | `tts_start`, `tts_done` |
| 10 | Turno siguiente o fin | hasta 8 turnos (3 conduciendo); "cancela", "eso es todo", silencio | — |

Cada turno registra un evento `latency` con todas las marcas → exportable desde la app (EXP-09).

## 4.2 Cascada (STT → LLM → TTS) vs voz a voz

| Criterio | Cascada (elegida para el MVP) | Voz a voz (Realtime/Live APIs) |
|---|---|---|
| Latencia | Suma de etapas; mitigada con STT y TTS locales y fast path | Menor y con *barge-in* nativo |
| Control de herramientas | Total: cada llamada pasa por scopes/política | Las herramientas también existen, pero el flujo es más difícil de auditar y de cortar por política |
| Sin cobertura | Fast path local sigue capturando ideas/notas/recordatorios | Nada funciona |
| Calidad con audio HFP (8/16 kHz, viento) | STT dedicado, VAD propio | Modelos de audio sensibles a ruido; banda estrecha |
| Costo | Bajo (texto) | Alto (audio por minuto) |
| Multiproveedor | Trivial (`ChatModel`) | API específica por proveedor |

**Recomendación**: cascada como base; evaluar un `RealtimeSession` opcional en modo DETENIDO para
conversación abierta, cuando EXP-04 confirme la calidad del micrófono.

## 4.3 Presupuesto de latencia (objetivos de diseño, NO mediciones)

| Etapa | Estimación | Qué la reduce |
|---|---|---|
| Abrir SCO | 0.3 – 1.5 s (desconocido para el EJEAS) | Mantener SCO durante toda la sesión, no por turno |
| Fin de habla (endpointing) | 0.7 – 0.9 s de silencio | Ajustable en `VadConfig.endSilenceMs` |
| STT en dispositivo | 0.2 – 0.6 s tras fin de habla | `EXTRA_PREFER_OFFLINE` |
| Fast path local | < 5 ms (medido en JVM con `gradle :core:bench`) | — |
| LLM (texto, esfuerzo bajo) | **pendiente de medir** (no hubo API key) | `effort: low`, tokens de salida acotados en conducción |
| Inicio de TTS local | 0.1 – 0.3 s | Motor local |

Meta: primera respuesta audible < 2 s desde el fin de habla para capturas (fast path) y lo más cerca
posible para consultas a la nube. Se medirá en EXP-09 con 10 repeticiones por tipo y p50/p90.

## 4.4 Tecnologías evaluadas

- **STT**: SpeechRecognizer de Android (local, streaming); OpenAI transcripción (nube, WAV); alternativas
  locales: Vosk (ligero, offline), whisper.cpp (más preciso, más CPU).
- **VAD**: `EnergyVad` propio (determinista, probado con ruido de viento simulado); alternativas:
  WebRTC VAD, Silero VAD (ONNX) detrás de `VoiceActivityDetector`.
- **TTS**: TextToSpeech del sistema (local); alternativas en la nube con voces más naturales a costa de latencia.
- **Cancelación de ruido**: el casco y el SoC ya aplican la suya en HFP; `VOICE_COMMUNICATION` añade la
  del teléfono (AEC/NS). Evaluar en EXP-04 si conviene `VOICE_RECOGNITION` (menos procesado).
- **Turnos e interrupciones**: "cancela/detente/silencio" se atiende antes que nada; el botón del
  casco o la notificación cortan TTS y captura de inmediato. *Barge-in* por voz durante el TTS queda en
  backlog (requiere AEC fiable sobre SCO).
