import { createServer, type IncomingMessage, type ServerResponse, type Server } from "node:http";
import { createHmac } from "node:crypto";
import { authenticate, hasScope, type TokenRecord } from "./auth.ts";
import { JsonStore, type Item } from "./store.ts";
import { context, str, tags } from "./validate.ts";

export type Config = {
  tokens: TokenRecord[];
  dataDir: string;
  /** Si se define, cada registro nuevo se reenvía firmado (HMAC-SHA256) a Control. */
  webhookUrl?: string;
  webhookSecret?: string;
  /** Peticiones por minuto por token. */
  rateLimitPerMinute?: number;
  log?: (msg: string) => void;
};

type Handler = (body: Record<string, unknown>, url: URL, token: TokenRecord) => { status: number; json: unknown };
type Route = { method: string; path: string; scope: string; handler: Handler };

const MAX_BODY = 32 * 1024;

export function createApp(cfg: Config): Server {
  const store = new JsonStore(cfg.dataDir);
  const log = cfg.log ?? ((m: string) => console.log(m));
  const buckets = new Map<string, { windowStart: number; count: number }>();
  const limit = cfg.rateLimitPerMinute ?? 120;

  const forward = (item: Item) => {
    if (!cfg.webhookUrl) return;
    const body = JSON.stringify(item);
    const sig = createHmac("sha256", cfg.webhookSecret ?? "").update(body).digest("hex");
    fetch(cfg.webhookUrl, { method: "POST", headers: { "content-type": "application/json", "x-control-signature": `sha256=${sig}` }, body })
      .then((r) => log(`webhook ${item.id} -> ${r.status}`))
      .catch((e) => log(`webhook ${item.id} falló: ${e.message}`));
  };

  const capture = (collection: string, kind: string, extraTags: string[] = []): Handler => (body) => {
    const errors: string[] = [];
    const text = str(body.text, "text", errors, { required: true });
    const ctx = context(body.context, errors);
    const t = tags(body.tags, errors);
    if (errors.length) return { status: 400, json: { error: "validation", details: errors } };
    const item = store.add(collection, kind, {
      text, tags: [...new Set([...t, ...extraTags])], context: ctx, researchLater: body.researchLater !== false, status: "stored",
    });
    forward(item);
    return { status: 201, json: { id: item.id, status: "stored" } };
  };

  const routes: Route[] = [
    { method: "POST", path: "/v1/ideas", scope: "control.ideas.write", handler: capture("ideas", "idea") },
    { method: "GET", path: "/v1/ideas", scope: "control.ideas.read", handler: () => ({ status: 200, json: { items: store.list("ideas").slice(-50).reverse() } }) },
    { method: "POST", path: "/v1/radar/ideas", scope: "control.radar.write", handler: capture("radar", "radar", ["china-opportunity-radar"]) },
    { method: "GET", path: "/v1/radar/ideas", scope: "control.radar.read", handler: () => ({ status: 200, json: { items: store.list("radar").slice(-50).reverse() } }) },
    { method: "POST", path: "/v1/notes", scope: "control.notes.write", handler: capture("notes", "note") },
    {
      method: "POST", path: "/v1/tasks", scope: "control.tasks.write", handler: (body) => {
        const errors: string[] = [];
        const title = str(body.title, "title", errors, { required: true, max: 300 });
        const due = str(body.due, "due", errors, { max: 64 });
        const trigger = str(body.trigger, "trigger", errors, { max: 120 });
        const ctx = context(body.context, errors);
        if (due && Number.isNaN(Date.parse(due))) errors.push("due debe ser fecha ISO-8601");
        if (errors.length) return { status: 400, json: { error: "validation", details: errors } };
        const item = store.add("tasks", "task", { title, due, trigger, context: ctx, done: false });
        forward(item);
        return { status: 201, json: { id: item.id, status: "stored" } };
      },
    },
    {
      method: "GET", path: "/v1/tasks/today", scope: "control.tasks.read", handler: () => {
        const today = new Date().toISOString().slice(0, 10);
        const items = store.list("tasks").filter((t) => !t.done && (!t.due || String(t.due).slice(0, 10) <= today));
        return { status: 200, json: { items: items.map((t) => ({ id: t.id, title: t.title, due: t.due ?? null, trigger: t.trigger ?? null })) } };
      },
    },
    {
      method: "POST", path: "/v1/results", scope: "control.results.write", handler: (body) => {
        const errors: string[] = [];
        const title = str(body.title, "title", errors, { required: true, max: 300 });
        const summary = str(body.summary, "summary", errors, { required: true });
        if (errors.length) return { status: 400, json: { error: "validation", details: errors } };
        const item = store.add("results", "result", { title, summary, sourceIdea: str(body.sourceIdea, "sourceIdea", errors, { max: 64 }) });
        return { status: 201, json: { id: item.id } };
      },
    },
    {
      method: "GET", path: "/v1/results/recent", scope: "control.results.read", handler: (_b, url) => {
        const n = Math.min(Math.max(Number(url.searchParams.get("limit") ?? 3) || 3, 1), 10);
        const items = store.list("results").slice(-n).reverse().map((r) => ({ id: r.id, title: r.title, summary: r.summary, updatedAt: r.createdAt }));
        return { status: 200, json: { items } };
      },
    },
    {
      method: "POST", path: "/v1/knowledge/query", scope: "control.knowledge.read", handler: (body) => {
        const errors: string[] = [];
        const q = str(body.question, "question", errors, { required: true, max: 500 });
        if (errors.length) return { status: 400, json: { error: "validation", details: errors } };
        return { status: 200, json: searchKnowledge(store, q!) };
      },
    },
  ];

  const send = (res: ServerResponse, status: number, json: unknown) => {
    const body = JSON.stringify(json);
    res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
    res.end(body);
  };

  return createServer(async (req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? "/", "http://local");
    if (req.method === "GET" && url.pathname === "/healthz") return send(res, 200, { ok: true });
    const route = routes.find((r) => r.method === req.method && r.path === url.pathname);
    if (!route) return send(res, 404, { error: "not_found" });

    const token = authenticate(req.headers.authorization, cfg.tokens);
    if (!token) return send(res, 401, { error: "unauthorized" });
    if (!hasScope(token, route.scope)) {
      log(`403 ${token.name} ${route.method} ${route.path} (falta ${route.scope})`);
      return send(res, 403, { error: "forbidden", requiredScope: route.scope });
    }
    const now = Date.now();
    const b = buckets.get(token.name) ?? { windowStart: now, count: 0 };
    if (now - b.windowStart > 60_000) { b.windowStart = now; b.count = 0; }
    b.count++;
    buckets.set(token.name, b);
    if (b.count > limit) return send(res, 429, { error: "rate_limited" });

    let body: Record<string, unknown> = {};
    if (req.method === "POST") {
      const chunks: Buffer[] = [];
      let size = 0;
      for await (const c of req) {
        size += (c as Buffer).length;
        if (size > MAX_BODY) return send(res, 413, { error: "payload_too_large" });
        chunks.push(c as Buffer);
      }
      try {
        const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
        if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error("not object");
        body = parsed;
      } catch {
        return send(res, 400, { error: "invalid_json" });
      }
    }
    try {
      const out = route.handler(body, url, token);
      log(`${out.status} ${token.name} ${route.method} ${route.path}`);
      send(res, out.status, out.json);
    } catch (e) {
      log(`500 ${route.path}: ${(e as Error).message}`);
      send(res, 500, { error: "internal" });
    }
  });
}

/**
 * Búsqueda por palabras clave sobre ideas, notas, radar y resultados.
 * Es un sustituto local: en producción esta ruta delega al agente de
 * conocimiento de Control.
 */
export function searchKnowledge(store: JsonStore, question: string): { answer: string; sources: string[] } {
  const words = normalize(question).split(/\s+/).filter((w) => w.length > 3);
  const pool = ["results", "ideas", "notes", "radar"].flatMap((c) => store.list(c));
  const scored = pool
    .map((it) => {
      const hay = normalize(`${it.title ?? ""} ${it.summary ?? ""} ${it.text ?? ""}`);
      return { it, score: words.filter((w) => hay.includes(w)).length };
    })
    .filter((s) => s.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);
  if (scored.length === 0) return { answer: "No encontré nada guardado sobre eso.", sources: [] };
  const answer = scored
    .map(({ it }) => (it.kind === "result" ? `${it.title}: ${it.summary}` : `${it.kind} del ${String(it.createdAt).slice(0, 10)}: ${it.text}`))
    .join(". ");
  return { answer, sources: scored.map(({ it }) => it.id) };
}

function normalize(s: string): string {
  return s.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ");
}
