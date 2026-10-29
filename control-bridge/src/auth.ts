import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export type TokenRecord = { name: string; sha256: string; scopes: string[] };

export function hashToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export function newToken(): string {
  return "ctl_" + randomBytes(24).toString("base64url");
}

/** Busca el token por hash con comparación en tiempo constante. */
export function authenticate(header: string | undefined, tokens: TokenRecord[]): TokenRecord | null {
  if (!header || !header.startsWith("Bearer ")) return null;
  const presented = Buffer.from(hashToken(header.slice(7).trim()), "hex");
  for (const t of tokens) {
    const stored = Buffer.from(t.sha256, "hex");
    if (stored.length === presented.length && timingSafeEqual(stored, presented)) return t;
  }
  return null;
}

/** Scopes con comodín: "control.*" cubre "control.ideas.write". */
export function hasScope(record: TokenRecord, scope: string): boolean {
  return record.scopes.some((s) => s === scope || (s.endsWith(".*") && scope.startsWith(s.slice(0, -1))));
}
