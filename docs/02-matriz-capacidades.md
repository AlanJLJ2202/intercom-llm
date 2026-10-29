# 2. Matriz de capacidades

Leyenda de estado:
- **CONFIRMADO**: evidencia capturada del EJEAS real (fuente `DEVICE`). *Hoy: ninguna.*
- **DOCUMENTADO**: lo afirma el fabricante o un tercero; no verificado en tu unidad.
- **HIPÓTESIS**: inferencia técnica plausible.
- **DESCONOCIDO**: sin información.
- **SOFTWARE LISTO**: el código para usar/medir la capacidad existe y tiene pruebas automatizadas, pero falta la prueba física.

| Capacidad | Estado en el dispositivo | Software | Experimento |
|---|---|---|---|
| Bluetooth Classic | DOCUMENTADO (BT 5.1) | Inventario SDP | EXP-01/02 |
| HFP (manos libres) | DOCUMENTADO indirecto (llamadas, auto-respuesta) | SOFTWARE LISTO (`BluetoothAudioRouter`) | EXP-02/04 |
| HSP | DESCONOCIDO | Inventario | EXP-02 |
| HFP wideband (mSBC 16 kHz) | HIPÓTESIS | `PcmCapture` intenta 16 kHz → 8 kHz y lo reporta | EXP-04 |
| A2DP Sink | DOCUMENTADO indirecto (música) | Salida A2DP en `SpeechOutput` | EXP-03 |
| AVRCP (botones multimedia) | DOCUMENTADO indirecto (control de música) | SOFTWARE LISTO (`MediaSession` + registro) | EXP-05 |
| Asistente del teléfono (AT+BVRA) | DOCUMENTADO ("voice assistant") sin botón identificado | SOFTWARE LISTO (`VoiceCommandActivity`) | EXP-05 |
| Comandos AT de fabricante (batería +XAPL/+IPHONEACCEV) | DESCONOCIDO | Registro de `ACTION_VENDOR_SPECIFIC_HEADSET_EVENT` | EXP-02/05 |
| BLE / GATT | DOCUMENTADO solo para V6 Pro+ (marketing) | SOFTWARE LISTO (`BleProbe`, `ble_gatt_dump.py`, solo lectura) | EXP-02 |
| Servicio propietario (SafeRiding) | HIPÓTESIS (BLE) | Sonda BLE | EXP-02 |
| HID | DESCONOCIDO (improbable) | Inventario | EXP-02 |
| LE Audio | DESCONOCIDO (improbable en BT 5.1) | Inventario (`isLeAudioSupported`, `TYPE_BLE_HEADSET`) | EXP-02 |
| Multipunto (2 teléfonos) | DESCONOCIDO | — | Backlog |
| Navegación GPS por intercom | DOCUMENTADO (V6 Pro+ y ficha V6 Pro) | — | — |
| Actualización de firmware USB-C | DOCUMENTADO | `usb_probe.py` (solo lectura de descriptores) | EXP-01 |
| Interrupción por llamada | — | SOFTWARE LISTO: pérdida de foco → cancela sesión | EXP-10 |
| Interrupción por intercom entre cascos | DESCONOCIDO (¿cae SCO?) | Monitor de eventos SCO/HFP | EXP-10 |
| Reconexión automática | DESCONOCIDO | Monitor ACL/HFP; la sesión termina limpiamente si cae HFP | EXP-10 |

## Capacidades del sistema de software (verificadas con pruebas automatizadas, sin hardware)

| Capacidad | Evidencia |
|---|---|
| Orquestador con bucle de herramientas, confirmaciones, cancelación y aplazamiento | `OrchestratorTest` (10 pruebas) |
| Política de conducción: 2 frases, sin markdown, riesgo alto aplazado | `PolicyAndRegistryTest` |
| Captura local (sin nube) de ideas, notas, radar, recordatorios en español | `LocalIntentModelTest` |
| Proveedor Claude: forma de la petición y lectura de `tool_use` (servidor simulado) | `AnthropicMappingTest` |
| Control: token Bearer, bandeja offline persistente y reenvío | `ControlClientTest` |
| Puente Control: scopes, validación, límite de peticiones, búsqueda | `control-bridge/test` (5 pruebas) |
| E2E: frase transcrita → orquestador → HTTP → puente Control con contexto | `scripts/e2e_control.sh` |
| VAD robusto a ruido de viento simulado, endpointing, pre-roll | `AudioAndLabTest` |
| Regla: un experimento físico no puede confirmarse sin evidencia del dispositivo | `AudioAndLabTest` |
| Fuentes Android compilan contra el framework (android-all API 35) | `gradle -Ptypecheck :app-typecheck:compileKotlin` |

**No verificado aún**: compilación con Android Gradle Plugin/APK, ejecución en un teléfono real,
latencia real de Claude (no había API key en el entorno).
