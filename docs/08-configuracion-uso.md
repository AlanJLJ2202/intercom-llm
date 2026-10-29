# 8. Configuración y uso

## Requisitos
- Teléfono Android 8.0+ (recomendado 12+ para `setCommunicationDevice`).
- Android Studio (Ladybug o posterior) con SDK 35, o Android SDK + Gradle 8.9+.
- EJEAS V6 Pro emparejado con el teléfono en Ajustes → Bluetooth.
- API key de Anthropic (opcional: sin ella, solo capturas locales). OpenAI solo si usas STT en la nube.
- Node.js ≥ 22.18 para `control-bridge`; Python 3.10+ para `lab/` (opcional: `bleak` para BLE).

## Compilar e instalar la app
```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:installDebug
```
(O abre `android/` en Android Studio y ejecuta *app*.) Si tu Android Studio ofrece actualizar el
Android Gradle Plugin, acéptalo: el proyecto usa APIs estándar.

## Verificaciones sin teléfono
```bash
cd android && gradle :core:test
```
```bash
cd android && gradle -Ptypecheck -PskipApp :app-typecheck:compileKotlin
```
```bash
cd control-bridge && npm test
```
```bash
python3 -m unittest discover -s lab/tests
```
```bash
./scripts/e2e_control.sh
```
```bash
cd android && ANTHROPIC_API_KEY=... gradle :core:bench -PbenchReps=10
```

## Primer uso (detenido)
1. Abre **Intercom IA**, concede permisos (micrófono, dispositivos cercanos, notificaciones).
2. Ajustes: API key de Anthropic; (opcional) URL y token de Control.
3. Laboratorio, en orden: Inventario → Escaneo BLE → Salida SCO → Salida A2DP → Grabar 5 s.
4. "Concluir un experimento…" para registrar lo que oíste (solo tú puedes confirmarlo).
5. **Armar modo ruta**. Prueba "Iniciar sesión ahora" y di "¿Qué hora es?".
6. EXP-05: "Elegir app de asistente" → selecciona Intercom IA si aparece; activa "botones multimedia como PTT".
7. Exporta la evidencia (JSON) y guárdala en `experiments/evidence/` (sin datos personales).

## Uso en ruta (después de validar EXP-01…10 estacionario)
- Antes de salir: modo **CONDUCCIÓN** y *Armar modo ruta*.
- Botón del casco (según EXP-05) → tono → habla → respuesta → tono de fin.
- "Cancela" corta todo. "Eso es todo" termina.
- Ideas, notas, radar y recordatorios funcionan **sin cobertura** (se sincronizan después).

## Puente Control
```bash
cd control-bridge
npm run token -- telefono control.ideas.write control.radar.write control.tasks.write control.tasks.read control.notes.write control.knowledge.read control.results.read
```
```bash
cd control-bridge && HOST=0.0.0.0 PORT=8787 npm start
```
Exponlo con HTTPS (Tailscale/Cloudflare Tunnel) y pon la URL y el token en la app.
