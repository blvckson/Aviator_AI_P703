const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const worker = fs.readFileSync(new URL("../worker.js", `file://${__filename}`), "utf8");
const start = worker.indexOf("function buildAppBuildingFallback(prompt, modelError) {");
const end = worker.length;
assert.ok(start >= 0 && end > start, "app-building fallback must be isolated before research fallback");
const buildAppBuildingFallback = vm.runInNewContext("(" + worker.slice(start, end).trim() + ")");

const inventoryPrompt = "Design a build-ready MVP for an Android offline-first inventory app for a small shop, with barcode/manual item entry, stock-in/out, low-stock alerts, CSV export, and optional later cloud sync.";

test("Android inventory fallback supplies architecture, schema, transaction, sync, validation, tests and build steps", () => {
  const answer = buildAppBuildingFallback(inventoryPrompt, new Error("provider timeout"));
  for (const term of [
    "architecture and data flow", "CREATE TABLE items", "CREATE TABLE stock_movements",
    "withTransaction", "idempotency", "offline", "conflict", "Validation and error handling",
    "Acceptance test — stock-in", "Acceptance test — stock-out", "assembleDebug",
    "not compiled or run", "provider timeout"
  ]) assert.match(answer, new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"));
  assert.ok((answer.match(/Acceptance test —/g) || []).length >= 8);
});

test("app-building fallback is limited to Android inventory and stock requests", () => {
  assert.equal(buildAppBuildingFallback("Write Python code for a sorting function", new Error("timeout")), "");
  assert.equal(buildAppBuildingFallback("Research Android MediaProjection requirements", new Error("timeout")), "");
  assert.equal(buildAppBuildingFallback("Build an unrelated compiler", new Error("timeout")), "");
});

test("provider failure routes build-mode requests to app-building fallback before generic research fallback", () => {
  assert.match(worker, /const appBuildingFallback = buildMode \? buildAppBuildingFallback\(prompt, modelError\) : ""/);
  assert.match(worker, /if \(appBuildingFallback\) \{[\s\S]*?responseText = appBuildingFallback/);
  assert.match(worker, /const engineeringFallback = !appBuildingFallback && engineeringTask/);
});
