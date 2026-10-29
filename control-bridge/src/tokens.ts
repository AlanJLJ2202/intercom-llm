// Crea un token con scopes y guarda SOLO su hash en tokens.json.
// Uso: node src/tokens.ts <nombre> <scope> [scope...]
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { hashToken, newToken, type TokenRecord } from "./auth.ts";

const [name, ...scopes] = process.argv.slice(2);
if (!name || scopes.length === 0) {
  console.error("Uso: node src/tokens.ts <nombre> <scope> [scope...]\nEj.: node src/tokens.ts telefono control.ideas.write control.radar.write control.tasks.write control.tasks.read control.notes.write control.knowledge.read control.results.read");
  process.exit(1);
}
const file = process.env.CONTROL_TOKENS_FILE ?? "tokens.json";
const tokens: TokenRecord[] = existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : [];
const token = newToken();
tokens.push({ name, sha256: hashToken(token), scopes });
writeFileSync(file, JSON.stringify(tokens, null, 2));
console.log(`Token para "${name}" (se muestra una sola vez):\n${token}`);
