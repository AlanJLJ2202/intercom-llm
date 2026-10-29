# 10. Backlog de investigación avanzada

## Prioridad alta (después de EXP-01…10)
1. **Reanudar tras intercom**: detectar fin del intercom (SCO libre) y ofrecer reanudar la sesión.
2. **Barge-in**: permitir interrumpir el TTS hablando (requiere AEC sobre SCO; evaluar `AcousticEchoCanceler`).
3. **STT local robusto**: comparar SpeechRecognizer vs Vosk vs whisper.cpp con grabaciones reales de casco (EXP-04).
4. **Proveedores**: `OpenAiChatModel`, `GeminiChatModel`, modelo local (Ollama/llama.cpp en LAN) detrás de `ChatModel`.
5. **Modo voz a voz** opcional en DETENIDO (Realtime/Live) con las mismas herramientas y scopes.

## Tercer nivel: capacidades del dispositivo (no bloquea el MVP)
6. **BLE de SafeRiding**: con el volcado GATT de solo lectura, catalogar servicios/características.
   Si hay notificaciones de estado (batería, canal de intercom), evaluar suscripción *de solo lectura*.
   Cualquier escritura queda como propuesta documentada, nunca automática.
7. **Captura HCI autorizada**: en *tu* teléfono, activar "Registro de Bluetooth HCI" (opciones de
   desarrollador), usar SafeRiding normalmente y analizar el `btsnoop_hci.log` con Wireshark para
   entender el protocolo de configuración. Sin extraer credenciales ni datos de terceros.
8. **Firmware oficial (análisis estático)**: descargar el paquete del modelo exacto, calcular hashes,
   `binwalk`/`strings` para identificar SoC, SDK y versión. **Sin flashear, sin forzar bootloader.**
9. **FCC ID**: si está en la etiqueta, revisar fotos internas e informe RF para identificar el SoC.
10. **Multipunto**: ¿puede el EJEAS mantener teléfono + GPS + intercom a la vez con nuestra sesión?

## Activación por voz (evaluada, aplazada)
11. Palabra de activación local (Porcupine, openWakeWord, Vosk KWS). Coste: SCO abierto de forma
    continua (música en calidad de llamada, batería del casco) y escucha ambiental: solo con
    consentimiento explícito y como opción en DETENIDO.

## Producto
12. Backend para no guardar API keys en el teléfono y auditar herramientas.
13. Detección de conducción como *señal adicional* (Android Auto, velocidad GPS) que solo eleve a CONDUCCIÓN.
14. Versión iOS con App Intents ("Oye Siri, Intercom IA").
