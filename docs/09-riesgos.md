# 9. Riesgos técnicos

| Riesgo | Prob. | Impacto | Mitigación / estado |
|---|---|---|---|
| El botón del EJEAS no envía AT+BVRA (o Android lo manda siempre al asistente predeterminado) | Media | Alto (activación manos libres) | A-bis: AVRCP vía `MediaSession`; C: botón en la app/notificación; iOS: Siri. EXP-05 decide |
| Android 14+ bloquea micrófono si la sesión se dispara con la app en segundo plano | Media | Alto | Servicio "modo ruta" armado desde la app visible (while-in-use durante su vida). Verificar en EXP-05 |
| El intercom entre cascos acapara el canal y corta SCO | Alta | Medio | Monitor de SCO/HFP; la sesión termina limpia; reintentar al terminar el intercom (backlog) |
| SCO en banda estrecha (CVSD 8 kHz) degrada el STT | Media | Medio | Detectar tasa real; STT en la nube como alternativa; frases cortas |
| `SpeechRecognizer` no usa el mic SCO | Media | Medio | Ruta CLOUD con `AudioRecord` explícito y `routedDevice` |
| Ruido de viento a velocidad | Alta | Medio | Cancelación del casco + VAD adaptativo; pruebas progresivas, nunca a alta velocidad sin validar |
| Latencia de la nube > 2 s en consultas abiertas | Media | Medio | Fast path local; `effort: low`; respuestas de 2 frases; medir en EXP-09 |
| API keys en el teléfono | Media | Medio | Almacenamiento privado, `allowBackup=false`. Producción: Keystore o backend que firme peticiones |
| Exposición del puente Control | Baja | Alto | Tokens con hash y scopes, HTTPS obligatorio, límite de peticiones, validación |
| Herramientas con efectos no deseados | Baja | Medio | Scopes otorgados explícitamente; riesgo alto = confirmación o aplazamiento; investigación desactivada por defecto |
| Distracción al conducir | Media | **Crítico** | Modo manual, respuestas cortas, sin UI en ruta, cancelación inmediata, pruebas solo estacionario |
| Diferencias V6 Pro vs Pro+ / firmware | Media | Medio | Registro por modelo y firmware en cada experimento |
| AGP 8.7.3 desactualizado en tu Android Studio | Baja | Bajo | Actualizar AGP desde el asistente; no hay APIs dependientes de versión |
| La app no se ha compilado como APK ni probado en teléfono | — | — | Verificado solo con android-all (tipos). Primer paso de la siguiente iteración |
