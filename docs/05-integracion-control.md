# 5. Integración con Control

## 5.1 Principio
No duplicar el motor de agentes de Control. El teléfono **solo captura y consulta** mediante una API
segura; la investigación profunda la hace Control después.

> No tuve acceso a la API real de Control en esta sesión. Por eso definí un **contrato mínimo**
> (`control-bridge/openapi.yaml`) y un **puente de referencia** que lo implementa y reenvía cada
> registro a Control por webhook firmado (HMAC-SHA256). Si Control ya expone una API, basta con
> implementar `ControlClient` contra ella o hacer que Control implemente este contrato.

## 5.2 Contrato v1

| Acción por voz | Herramienta | Endpoint | Scope | Riesgo |
|---|---|---|---|---|
| "Guarda esta idea…" | `save_idea` | `POST /v1/ideas` | `control.ideas.write` | Bajo |
| "Idea para el radar…" | `send_to_radar` | `POST /v1/radar/ideas` | `control.radar.write` | Bajo |
| "Recuérdame… al llegar a casa" | `create_task` | `POST /v1/tasks` | `control.tasks.write` | Bajo |
| "Registra una nota de voz…" | `dictate_note` | `POST /v1/notes` | `control.notes.write` | Bajo |
| "¿Qué tengo pendiente hoy?" | `pending_today` | `GET /v1/tasks/today` | `control.tasks.read` | Bajo |
| "Pregúntale a Control qué encontramos…" | `ask_knowledge_agent` | `POST /v1/knowledge/query` | `control.knowledge.read` | Bajo |
| "¿Qué resultados hay?" | `recent_results` | `GET /v1/results/recent` | `control.results.read` | Bajo |
| "Investiga a fondo…" | `start_deep_research` | `POST /v1/tasks` (trigger `research`) | `control.research.start` (**no otorgado por defecto**) | Alto: se aplaza conduciendo; confirmación detenido |

Cada captura incluye `context`: frase original, modo (DRIVING/STOPPED), hora, id de sesión y fuente.
Ejemplo real de la prueba E2E (`scripts/e2e_control.sh`, sin hardware):

```json
{"text":"un dispositivo que traduce conversaciones mientras viajo. Investígalo después",
 "tags":["radar","china-opportunity-radar"],
 "context":{"utterance":"Encontré una idea para el radar: un dispositivo que traduce conversaciones mientras viajo. Investígalo después",
            "mode":"DRIVING","sessionId":"…","source":"ejeas-v6-pro-voice"},
 "researchLater":true,"status":"stored","id":"radar_…"}
```

## 5.3 Seguridad
- Tokens Bearer generados con `npm run token`; el servidor guarda **solo el hash SHA-256**; comparación en tiempo constante.
- Scope por ruta; comodines (`control.tasks.*`) permitidos; 403 indica el scope requerido.
- Validación de tipos y longitudes; cuerpo ≤ 32 KB; límite de peticiones por token.
- El modelo **nunca ve** herramientas cuyo scope no se otorgó en la app (doble barrera: app y servidor).
- Acciones de riesgo alto: confirmación por voz (detenido) o aplazamiento (conduciendo).
- Sin cobertura: escrituras a una **bandeja local persistente** que se reenvía al terminar la sesión.

## 5.4 Despliegue sugerido
1. `cd control-bridge && npm run token -- telefono control.ideas.write control.radar.write control.tasks.write control.tasks.read control.notes.write control.knowledge.read control.results.read`
2. `CONTROL_WEBHOOK_URL=https://<control>/hooks/voice CONTROL_WEBHOOK_SECRET=… npm start`
3. Exponerlo con HTTPS (Tailscale, Cloudflare Tunnel o reverse proxy). Android bloquea HTTP en claro salvo `localhost`/`10.0.2.2`.
4. En la app: URL y token de Control.
5. Control publica resúmenes de investigación con `POST /v1/results` (scope `control.results.write`) para que "¿qué encontramos?" los lea.
