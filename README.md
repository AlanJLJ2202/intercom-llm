# intercom-llm — EJEAS V6 Pro como interfaz de voz para agentes de IA

Habla por el micrófono del casco, un agente de IA coordinado desde el teléfono responde por los
altavoces del casco y puede ejecutar herramientas reales (guardar ideas en **Control**, enviar al
**China Opportunity Radar**, crear recordatorios), con políticas estrictas para **modo conducción**.

> **Estado honesto (29-oct-2026)**: software implementado y probado sin hardware. **Ningún experimento
> físico se ha ejecutado todavía** (no había intercomunicador ni teléfono conectados). La app Android
> se verificó compilando sus fuentes contra el framework de Android (API 35), no como APK en un teléfono.

## Qué hay aquí

| Ruta | Contenido |
|---|---|
| `android/core` | Núcleo JVM puro: orquestador, políticas DETENIDO/CONDUCCIÓN, herramientas con scopes, Claude (SDK oficial), adaptador Control con bandeja offline, VAD, STT en la nube, registro de experimentos. **39 pruebas.** |
| `android/app` | App Android (Kotlin, sin AndroidX): laboratorio Bluetooth (SDP, BLE/GATT solo lectura, eventos HFP/AT), enrutado SCO, captura, TTS, servicio "modo ruta", disparadores por botón/intent |
| `android/app-typecheck` | Verifica tipos de la app sin Android SDK |
| `control-bridge` | Puente de referencia hacia Control (Node 22, sin dependencias): tokens con hash y scopes, validación, webhook firmado. **5 pruebas.** |
| `lab` | Herramientas para PC Linux: `bt_inventory.py`, `ble_gatt_dump.py`, `usb_probe.py`. **5 pruebas.** |
| `scripts/e2e_control.sh` | E2E: frase → orquestador Kotlin → HTTP → puente Control |
| `experiments/registry.json` | Registro de los 10 experimentos (todos PENDIENTES; evidencia documental y de simulación) |
| `docs/` | Entregables |

## Entregables

1. [Informe técnico del EJEAS V6 Pro](docs/01-informe-tecnico-ejeas-v6-pro.md)
2. [Matriz de capacidades](docs/02-matriz-capacidades.md)
3. [Arquitectura](docs/03-arquitectura.md)
4. Aplicación: `android/` · 5. Diagnóstico Bluetooth: app *Laboratorio* + `lab/`
6. [Pipeline de voz](docs/04-pipeline-voz.md) · 7. Proveedor IA: Claude (`AnthropicChatModel`)
8. [Integración con Control](docs/05-integracion-control.md) · [Seguridad y conducción](docs/06-seguridad-conduccion.md)
9. [Experimentos y resultados](docs/07-experimentos.md)
10. [Configuración y uso](docs/08-configuracion-uso.md)
11. [Riesgos](docs/09-riesgos.md) · 12. [Backlog de investigación](docs/10-backlog-investigacion.md)

## Inicio rápido (sin hardware)
```bash
cd android && gradle :core:test
```
```bash
cd control-bridge && npm test
```
```bash
./scripts/e2e_control.sh
```

## Siguiente paso con el casco
Compila la app en Android Studio, empareja el EJEAS y ejecuta en orden, **estacionado**:
Inventario → Escaneo BLE → Salida SCO/A2DP → Grabar 5 s → pruebas de botón → "¿Qué hora es?" →
"Encontré una idea para el radar: …". Exporta la evidencia y súbela a `experiments/evidence/`.
