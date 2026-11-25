/**
 * Builds the Princeton WordNet 3.0 ILI registry that the backend loads into
 * `wordnet_ili` / `wordnet_lemma` at startup (see WordnetRegistryLoader).
 *
 * Usage (from the backend repository root):
 *   1. Download https://wordnetcode.princeton.edu/3.0/WNdb-3.0.tar.gz
 *   2. Extract it so that <dir>/dict/data.noun exists
 *   3. node scripts/wordnet/build-wn30-registry.mjs <dir>/dict
 *
 * Output (committed, baked into the backend jar / Docker image):
 *   src/main/resources/wordnet/wn30-synsets.tsv.gz   ili, pos, words (" | " separated), gloss
 *   src/main/resources/wordnet/wn30-lemmas.tsv.gz    lemma, pos, ili, sense rank
 *   src/main/resources/wordnet/wn30-registry.properties   version + row counts
 *
 * ILI format used by the Bengali LKC (and the UKC seed data):
 *   {8-digit-zero-padded-offset}-{n|v|a|r}; adjective satellites ("s") use "a".
 */
import { createReadStream, mkdirSync, writeFileSync, existsSync } from "node:fs";
import { createInterface } from "node:readline";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, "..", "..");
const OUT_DIR = join(ROOT, "src", "main", "resources", "wordnet");
const DICT = process.argv[2];

if (!DICT || !existsSync(join(DICT, "data.noun"))) {
  console.error("Usage: node scripts/wordnet/build-wn30-registry.mjs <path-to-WordNet-3.0-dict>");
  process.exit(1);
}

const POS_FILES = [
  { suffix: "noun", pos: "n" },
  { suffix: "verb", pos: "v" },
  { suffix: "adj", pos: "a" },
  { suffix: "adv", pos: "r" },
];

/** Display form of a WordNet word: underscores to spaces, adjective markers "(p)", "(a)", "(ip)" removed. */
function displayWord(raw) {
  return raw.replace(/_/g, " ").replace(/\((?:a|p|ip)\)$/, "").trim();
}

/** Search key of a WordNet lemma. */
function lemmaKey(raw) {
  return displayWord(raw).toLowerCase().replace(/\s+/g, " ");
}

function clean(text) {
  return text.replace(/[\t\r\n]+/g, " ").replace(/\s+/g, " ").trim();
}

async function lines(path) {
  const rl = createInterface({ input: createReadStream(path, { encoding: "utf8" }), crlfDelay: Infinity });
  const out = [];
  for await (const line of rl) {
    if (line && !line.startsWith("  ")) out.push(line);
  }
  return out;
}

async function main() {
  const synsets = new Map();
  for (const { suffix, pos } of POS_FILES) {
    for (const line of await lines(join(DICT, `data.${suffix}`))) {
      if (!/^\d{8} /.test(line)) continue;
      const bar = line.indexOf(" | ");
      const head = (bar >= 0 ? line.slice(0, bar) : line).trim().split(/\s+/);
      const glossFull = bar >= 0 ? line.slice(bar + 3) : "";
      // Keep the definition only; usage examples follow as `; "..."`.
      const gloss = clean(glossFull.replace(/\s*;\s*".*$/, "").replace(/;\s*$/, ""));
      const offset = head[0];
      const wCnt = parseInt(head[3], 16);
      const words = [];
      for (let i = 0; i < wCnt; i++) {
        const w = displayWord(head[4 + i * 2] ?? "");
        if (w && !words.includes(w)) words.push(w);
      }
      if (words.length === 0) throw new Error(`No words for ${offset}-${pos}`);
      for (const w of words) {
        if (w.includes("|") || w.includes("\t")) throw new Error(`Unexpected separator in word "${w}"`);
      }
      const ili = `${offset}-${pos}`;
      if (synsets.has(ili)) throw new Error(`Duplicate ILI ${ili}`);
      synsets.set(ili, { pos, words, gloss });
    }
    console.log(`data.${suffix}: ${[...synsets.values()].filter((s) => s.pos === pos).length} synsets`);
  }

  const lemmaRows = [];
  const seen = new Set();
  for (const { suffix, pos } of POS_FILES) {
    for (const line of await lines(join(DICT, `index.${suffix}`))) {
      const parts = line.trim().split(/\s+/);
      if (parts.length < 6) continue;
      const lemma = lemmaKey(parts[0]);
      const synsetCnt = parseInt(parts[2], 10);
      const pCnt = parseInt(parts[3], 10);
      const offsets = parts.slice(4 + pCnt + 2, 4 + pCnt + 2 + synsetCnt);
      if (offsets.length !== synsetCnt) throw new Error(`Bad index line: ${line}`);
      offsets.forEach((offset, i) => {
        const ili = `${offset}-${pos}`;
        if (!synsets.has(ili)) throw new Error(`Index points to unknown synset ${ili} (${lemma})`);
        const key = `${lemma}\t${ili}`;
        if (seen.has(key)) return;
        seen.add(key);
        lemmaRows.push(`${lemma}\t${pos}\t${ili}\t${i + 1}`);
      });
    }
  }

  const synsetRows = [...synsets.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([ili, s]) => `${ili}\t${s.pos}\t${s.words.join(" | ")}\t${s.gloss}`);

  mkdirSync(OUT_DIR, { recursive: true });
  const synsetGz = gzipSync(synsetRows.join("\n") + "\n", { level: 9 });
  const lemmaGz = gzipSync(lemmaRows.join("\n") + "\n", { level: 9 });
  writeFileSync(join(OUT_DIR, "wn30-synsets.tsv.gz"), synsetGz);
  writeFileSync(join(OUT_DIR, "wn30-lemmas.tsv.gz"), lemmaGz);
  const lemmaCount = new Set(lemmaRows.map((r) => r.split("\t")[0])).size;
  writeFileSync(
    join(OUT_DIR, "wn30-registry.properties"),
    [
      "# Generated by scripts/wordnet/build-wn30-registry.mjs — do not edit by hand.",
      "# Source: Princeton WordNet 3.0 (https://wordnetcode.princeton.edu/3.0/WNdb-3.0.tar.gz)",
      "version=3.0",
      `synsetCount=${synsetRows.length}`,
      `lemmaRowCount=${lemmaRows.length}`,
      `lemmaCount=${lemmaCount}`,
      "",
    ].join("\n")
  );
  console.log(
    `\n${synsetRows.length} synsets (${(synsetGz.length / 1048576).toFixed(1)} MB gz), ` +
      `${lemmaRows.length} lemma rows / ${lemmaCount} lemmas (${(lemmaGz.length / 1048576).toFixed(1)} MB gz)`
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
