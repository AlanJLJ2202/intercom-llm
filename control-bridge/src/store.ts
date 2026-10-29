import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

export type Item = { id: string; kind: string; createdAt: string; [k: string]: unknown };

/** Almacenamiento JSON por colección con escritura atómica (tmp + rename). */
export class JsonStore {
  private readonly dir: string;
  private cache = new Map<string, Item[]>();

  constructor(dir: string) {
    this.dir = dir;
    mkdirSync(dir, { recursive: true });
  }

  list(collection: string): Item[] {
    if (!this.cache.has(collection)) {
      const file = join(this.dir, `${collection}.json`);
      this.cache.set(collection, existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Item[]) : []);
    }
    return this.cache.get(collection)!;
  }

  add(collection: string, kind: string, data: Record<string, unknown>): Item {
    const item: Item = { ...data, id: `${kind}_${randomUUID().slice(0, 8)}`, kind, createdAt: new Date().toISOString() };
    const items = this.list(collection);
    items.push(item);
    const file = join(this.dir, `${collection}.json`);
    writeFileSync(file + ".tmp", JSON.stringify(items, null, 2));
    renameSync(file + ".tmp", file);
    return item;
  }
}
