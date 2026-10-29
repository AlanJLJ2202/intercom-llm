# 7. Experimentos y resultados

Metodología: **observar → hipótesis → experimentar → medir → documentar → iterar**.
Cada experimento vive en el registro estructurado de la app (`ExperimentLog`) y en
`experiments/registry.json`. Regla aplicada por código: un experimento físico **no puede** quedar
CONFIRMADO/REFUTADO sin evidencia capturada del dispositivo (`EvidenceSource.DEVICE`).

## Estado al 29-oct-2026

| ID | Experimento | Estado | Evidencia disponible |
|---|---|---|---|
| EXP-01 | Identificar dispositivo y revisión | **PENDIENTE** (físico) | Documental (§1). Herramientas: app *Inventario*, `bt_inventory.py`, `usb_probe.py` |
| EXP-02 | Perfiles Bluetooth | **PENDIENTE** (físico) | Herramientas: SDP, BLE + GATT solo lectura |
| EXP-03 | Reproducir audio en el casco | **PENDIENTE** (físico) | Botones de prueba SCO / A2DP |
| EXP-04 | Capturar el micrófono del casco | **PENDIENTE** (físico) | Grabación loopback + `routedDevice` + dBFS |
| EXP-05 | Activación por botón | **PENDIENTE** (físico) | `VoiceCommandActivity` + `MediaSession` registran lo que llega |
| EXP-06 | Conversación con IA | **PENDIENTE** (físico) | Software listo; contrato con Claude verificado con servidor simulado |
| EXP-07 | Herramienta sencilla | **PENDIENTE** (físico) | Simulación: `current_time` por fast path (prueba automatizada) |
| EXP-08 | Guardar idea en Control | **PENDIENTE** (físico) | Simulación E2E real contra el puente (abajo) |
| EXP-09 | Latencia | **PENDIENTE** (físico) | Fast path medido en JVM; nube sin medir |
| EXP-10 | Reconexión e interrupciones | **PENDIENTE** (físico) | Monitor de eventos + cancelación por foco/HFP |

### Evidencia de simulación registrada (no sustituye la física)

**EXP-08 (E2E software)** — `./scripts/e2e_control.sh`, ejecutado en este entorno:
```
Respuesta hablada: Listo. Enviado al radar.
201 e2e-phone POST /v1/radar/ideas
200 e2e-reader GET /v1/radar/ideas
```
El registro en Control conserva frase original, modo DRIVING, hora e id de sesión.

**EXP-09 (orquestador, fast path)** — `gradle :core:bench`, sin API key: capturas locales y hora
resueltas en 0–1 ms (p50/p90, n=3). Las consultas abiertas requieren la nube y **no se midieron**.

## Protocolo detallado

### EXP-01 — Identificar dispositivo y revisión
- **Hipótesis**: nombre BT, clase, OUI, Device ID y etiqueta permiten distinguir V6 Pro de V6 Pro+.
- **Procedimiento**: (1) foto de etiqueta/caja; (2) app → *Inventario Bluetooth*; (3) PC: `bt_inventory.py`;
  (4) `usb_probe.py --before/--after`; (5) versión de firmware en SafeRiding.
- **Evidencia esperada**: JSON de inventario, fotos, salida USB.
- **Resultado**: pendiente. **Siguiente**: con el OUI/Device ID, buscar FCC ID y SoC.

### EXP-02 — Perfiles Bluetooth
- **Hipótesis**: HFP (0x111E), A2DP Sink (0x110B), AVRCP (0x110C/0x110E); BLE propietario para SafeRiding.
- **Procedimiento**: *Inventario* (fuerza SDP fresco) y *Escaneo BLE + GATT*; en PC `sdptool browse` para versión HFP.
- **Criterio**: CONFIRMADO si los UUIDs aparecen en la consulta SDP del dispositivo real.

### EXP-03 — Reproducir audio
- **Hipótesis**: TTS con `USAGE_VOICE_COMMUNICATION` sale por SCO; con `USAGE_ASSISTANT`, por A2DP.
- **Procedimiento**: botones *Probar salida SCO/A2DP*; anotar si se oye en el casco y con qué calidad.
- **Criterio**: el usuario confirma audición; el registro muestra `routeOk` y el dispositivo de comunicación.

### EXP-04 — Micrófono del casco
- **Hipótesis**: con SCO activo, `AudioRecord(VOICE_COMMUNICATION)` recibe audio del casco a 16 kHz (mSBC) u 8 kHz.
- **Procedimiento**: *Grabar 5 s y reproducir* con cada estrategia de ruta (comm device / voice recognition / ambas).
  Hablar 3 s y callar 2 s. Repetir con el teléfono en otra habitación para descartar su micrófono.
- **Criterio**: `routedDevice.type = BLUETOOTH_SCO`, RMS al hablar ≥ 15 dB sobre silencio, y la voz se
  oye en la reproducción **estando el teléfono lejos**.

### EXP-05 — Botón
- **Hipótesis A**: algún gesto del botón Teléfono (o combinación) envía AT+BVRA=1 → intent de voz.
- **Hipótesis A-bis**: play/pausa o siguiente llegan como AVRCP a nuestra `MediaSession`.
- **Procedimiento**: elegir la app como asistente/comandos de voz; armar modo ruta; activar "botones
  multimedia como PTT"; probar: toque, doble toque, pulsación 1 s / 3 s de cada botón. Anotar en una tabla.
- **Riesgo conocido**: la pulsación de 3 s del botón Teléfono hace **rellamada**: probar con el teléfono
  en modo avión o sin número reciente.

### EXP-06 / 07 / 08 — Conversación, herramienta, Control
- **Procedimiento**: configurar API key y Control; detenido, iniciar sesión; decir "¿Qué hora es?",
  "Explícame brevemente qué es un transformer", "Encontré una idea para el radar: …".
- **Criterio**: se oye respuesta en el casco; eventos `tool:*` en el registro; registro visible en `GET /v1/radar/ideas`.

### EXP-09 — Latencia
- 10 repeticiones por tipo (captura local, hora, pregunta abierta), modo DETENIDO y CONDUCCIÓN.
- Exportar eventos `latency`; calcular p50/p90 de `speechEndToTts` y de `activation→tts_start`.

### EXP-10 — Reconexión e interrupciones
- Durante una sesión: apagar el casco; recibir llamada; iniciar intercom con otro casco; cortar datos.
- **Esperado**: llamada → cancela por foco; HFP caído → termina limpio; sin datos → captura local + bandeja offline.
