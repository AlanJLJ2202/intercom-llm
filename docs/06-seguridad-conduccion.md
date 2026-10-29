# 6. Seguridad y conducción

## Principios
1. **El modo es manual.** No se asume que el sistema detecta si conduces. Señales futuras (velocidad
   GPS, Android Auto) solo podrían *subir* a CONDUCCIÓN, nunca bajar a DETENIDO.
2. **Cero interacción visual.** En ruta todo es voz, tonos y botones del casco.
3. **Cancelar siempre gana.** "Cancela/detente/silencio/cállate", el botón del casco o "Detener" en la
   notificación cortan TTS, captura y reconocimiento de inmediato.
4. **La comunicación prioritaria gana.** Una pérdida de foco de audio (llamada, navegación) cancela la
   sesión; no se inicia sesión durante una llamada.
5. **Pruebas solo en estacionario** hasta completar EXP-01…EXP-10.

## Diferencias por modo (implementadas en `InteractionPolicy`)

| | DETENIDO | CONDUCCIÓN |
|---|---|---|
| Respuesta hablada | hasta 12 frases / 1200 caracteres | **2 frases / 220 caracteres** |
| Tokens de salida | 1200 | 300 |
| Rondas de herramientas | 5 | 2 |
| Riesgo bajo | ejecuta | ejecuta |
| Riesgo medio | confirma por voz | confirma por voz |
| Riesgo alto | confirma por voz | **se aplaza** hasta detenerse (o se niega si no es aplazable) |
| Turnos por sesión | 8 | 3 |
| Prompt | explicaciones claras | "máximo dos frases, nada que requiera mirar el teléfono" |

Los aplazados se anuncian al iniciar la siguiente sesión en modo DETENIDO ("Tienes N pendientes…")
y se ejecutan con "ejecuta pendientes".

## Audio y entorno
- Tonos cortos (120 ms) a volumen moderado; sin música de espera.
- Foco transitorio: la música se pausa solo durante la sesión.
- Salida por SCO por defecto (misma ruta que el micrófono, sin reconfiguraciones a mitad de sesión).

## Lo que NO hace el sistema
- No escucha de forma ambiental ni permanente (sin palabra de activación).
- No inicia tareas que requieran manipular el teléfono.
- No modifica firmware ni escribe en el intercomunicador.
