import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AddressInfo } from "node:net";
import { createApp } from "../src/app.ts";
import { hashToken } from "../src/auth.ts";

const PHONE = "phone-token";
const READONLY = "read-token";

async function start() {
  const server = createApp({
    dataDir: mkdtempSync(join(tmpdir(), "ctl-")),
    tokens: [
      { name: "phone", sha256: hashToken(PHONE), scopes: ["control.ideas.write", "control.radar.write", "control.tasks.*", "control.knowledge.read", "control.results.read", "control.ideas.read"] },
      { name: "ro", sha256: hashToken(READONLY), scopes: ["control.results.read"] },
    ],
    rateLimitPerMinute: 50,
    log: () => {},
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const call = (method: string, path: string, token?: string, body?: unknown) =>
    fetch(base + path, {
      method,
      headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  return { server, call };
}

const ctx = { utterance: "Encontré una idea para el radar: traductor de viaje", mode: "DRIVING", capturedAt: "2026-10-29T10:00:00-06:00", sessionId: "s1" };

test("guarda idea de radar con contexto y la lista", async () => {
  const { server, call } = await start();
  try {
    const r = await call("POST", "/v1/radar/ideas", PHONE, { text: "traductor de viaje", tags: ["viaje"], context: ctx });
    assert.equal(r.status, 201);
    const { id } = (await r.json()) as { id: string };
    assert.match(id, /^radar_/);
    const ideas = await call("POST", "/v1/ideas", PHONE, { text: "rampa para motos", context: ctx });
    assert.equal(ideas.status, 201);
    const list = (await (await call("GET", "/v1/ideas", PHONE)).json()) as { items: { text: string; context: { mode: string } }[] };
    assert.equal(list.items[0].text, "rampa para motos");
    assert.equal(list.items[0].context.mode, "DRIVING");
  } finally { server.close(); }
});

test("rechaza sin token, token inválido y scope insuficiente", async () => {
  const { server, call } = await start();
  try {
    assert.equal((await call("POST", "/v1/ideas", undefined, { text: "x" })).status, 401);
    assert.equal((await call("POST", "/v1/ideas", "nope", { text: "x" })).status, 401);
    const r = await call("POST", "/v1/ideas", READONLY, { text: "x", context: ctx });
    assert.equal(r.status, 403);
    assert.equal(((await r.json()) as { requiredScope: string }).requiredScope, "control.ideas.write");
  } finally { server.close(); }
});

test("valida el cuerpo", async () => {
  const { server, call } = await start();
  try {
    const r = await call("POST", "/v1/tasks", PHONE, { title: "", due: "mañana", context: { ...ctx, mode: "FLYING" } });
    assert.equal(r.status, 400);
    const details = ((await r.json()) as { details: string[] }).details.join(" ");
    assert.match(details, /title/);
    assert.match(details, /context.mode/);
    const bad = await fetch((server.address() as AddressInfo).port ? `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1/ideas` : "", {
      method: "POST", headers: { authorization: `Bearer ${PHONE}` }, body: "{no json",
    });
    assert.equal(bad.status, 400);
  } finally { server.close(); }
});

test("tareas de hoy y consulta de conocimiento", async () => {
  const { server, call } = await start();
  try {
    await call("POST", "/v1/tasks", PHONE, { title: "investigar traductor", trigger: "al llegar a casa", context: ctx });
    await call("POST", "/v1/tasks", PHONE, { title: "futuro", due: "2099-01-01T00:00:00Z", context: ctx });
    const today = (await (await call("GET", "/v1/tasks/today", PHONE)).json()) as { items: { title: string }[] };
    assert.deepEqual(today.items.map((t) => t.title), ["investigar traductor"]);

    await call("POST", "/v1/ideas", PHONE, { text: "traductor simultáneo para viajes por China", context: ctx });
    const k = (await (await call("POST", "/v1/knowledge/query", PHONE, { question: "¿Qué encontramos sobre el traductor?" })).json()) as { answer: string; sources: string[] };
    assert.match(k.answer, /traductor/);
    assert.equal(k.sources.length, 1);
  } finally { server.close(); }
});

test("límite de peticiones por token", async () => {
  const { server, call } = await start();
  try {
    let last = 0;
    for (let i = 0; i < 52; i++) last = (await call("GET", "/v1/results/recent", READONLY)).status;
    assert.equal(last, 429);
  } finally { server.close(); }
});
