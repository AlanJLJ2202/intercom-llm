/** Validación mínima y explícita (sin dependencias). Devuelve lista de errores. */
export type Captured = { utterance: string; mode: string; capturedAt: string; sessionId: string; source?: string };

const MAX_TEXT = 4000;

export function str(v: unknown, field: string, errors: string[], opts: { required?: boolean; max?: number } = {}): string | undefined {
  if (v === undefined || v === null || v === "") {
    if (opts.required) errors.push(`${field} es obligatorio`);
    return undefined;
  }
  if (typeof v !== "string") { errors.push(`${field} debe ser texto`); return undefined; }
  if (v.length > (opts.max ?? MAX_TEXT)) { errors.push(`${field} excede ${opts.max ?? MAX_TEXT} caracteres`); return undefined; }
  return v.trim();
}

export function context(v: unknown, errors: string[]): Captured | undefined {
  if (v === undefined) return undefined;
  if (typeof v !== "object" || v === null) { errors.push("context debe ser objeto"); return undefined; }
  const c = v as Record<string, unknown>;
  const mode = str(c.mode, "context.mode", errors, { required: true, max: 20 });
  if (mode && mode !== "DRIVING" && mode !== "STOPPED") errors.push("context.mode debe ser DRIVING o STOPPED");
  return {
    utterance: str(c.utterance, "context.utterance", errors, { required: true }) ?? "",
    mode: mode ?? "",
    capturedAt: str(c.capturedAt, "context.capturedAt", errors, { required: true, max: 64 }) ?? "",
    sessionId: str(c.sessionId, "context.sessionId", errors, { required: true, max: 64 }) ?? "",
    source: str(c.source, "context.source", errors, { max: 64 }),
  };
}

export function tags(v: unknown, errors: string[]): string[] {
  if (v === undefined) return [];
  if (!Array.isArray(v) || v.some((t) => typeof t !== "string" || t.length > 40) || v.length > 20) {
    errors.push("tags debe ser lista de hasta 20 textos cortos");
    return [];
  }
  return v.map((t: string) => t.trim()).filter(Boolean);
}
