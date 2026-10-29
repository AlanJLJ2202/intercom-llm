import { readFileSync, existsSync } from "node:fs";
import { createApp } from "./app.ts";
import type { TokenRecord } from "./auth.ts";

const tokensFile = process.env.CONTROL_TOKENS_FILE ?? "tokens.json";
if (!existsSync(tokensFile)) {
  console.error(`Falta ${tokensFile}. Crea un token con: npm run token -- <nombre> <scope> [scope...]`);
  process.exit(1);
}
const tokens = JSON.parse(readFileSync(tokensFile, "utf8")) as TokenRecord[];
const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "127.0.0.1";

createApp({
  tokens,
  dataDir: process.env.CONTROL_DATA_DIR ?? "data",
  webhookUrl: process.env.CONTROL_WEBHOOK_URL,
  webhookSecret: process.env.CONTROL_WEBHOOK_SECRET,
}).listen(port, host, () => console.log(`control-bridge escuchando en http://${host}:${port} (${tokens.length} tokens)`));
