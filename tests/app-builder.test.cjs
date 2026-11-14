const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const builder = fs.readFileSync("public/app-builder.js", "utf8");
const html = fs.readFileSync("public/index.html", "utf8");
const chat = fs.readFileSync("public/app.js", "utf8");

test("dedicated app builder UI is separate from the normal Ask.Me chat", () => {
  for (const id of ["app-builder-platform","app-builder-spec","app-builder-run","app-builder-output","app-builder-files","app-builder-preview"]) {
    assert.ok(html.includes('id="' + id + '"'), "missing builder UI: " + id);
  }
  assert.match(html, /<script src="app-builder\.js"><\/script>/);
  assert.match(builder, /const API_BASE = "https:\/\/freeapp-ai\.lovesongmelodies\.workers\.dev"/);
  assert.doesNotMatch(chat, /app-builder/);
});

test("builder supports web, Android and full-stack project types", () => {
  for (const type of ['value="web"','value="android"','value="fullstack"']) assert.ok(html.includes(type));
  assert.match(builder, /Target Android/);
  assert.match(builder, /Target a full-stack web app/);
  assert.match(builder, /Target a browser-runnable web app/);
});

test("builder requests complete multi-file source, tests and honest build status", () => {
  assert.match(builder, /SOURCE-FIRST OUTPUT/);
  assert.match(builder, /automated tests and exact local build\/run commands/);
  assert.match(builder, /Never claim a build passed unless it was actually built/);
  assert.match(builder, /Do not say code was compiled, tested, installed, or deployed unless the operation actually happened/);
});

test("builder extracts safe labelled files and offers downloads", () => {
  assert.match(builder, /function extractFiles\(answer, appType\)/);
  assert.match(builder, /filePath\.startsWith\("\/"\)/);
  assert.match(builder, /filePath\.split/);
  assert.match(builder, /includes\("\.\."\)/);
  assert.match(builder, /function downloadFile\(file\)/);
  assert.match(builder, /link\.download/);
});

test("web app output can be previewed in a sandboxed iframe", () => {
  assert.match(html, /id="app-builder-preview"[^>]*sandbox="allow-scripts"/);
  assert.match(builder, /function buildPreview\(files\)/);
  assert.match(builder, /preview\.srcdoc = previewDocument/);
});

test("builder has request timeout, input bounds, and never touches GitHub or chat state", () => {
  assert.match(builder, /description\.length < 20/);
  assert.match(builder, /description\.length > 12000/);
  assert.match(builder, /setTimeout\(\(\) => controller\.abort\(\), 180000\)/);
  assert.doesNotMatch(builder, /github-token|github-agent|localStorage|AskMeNativeTTS|speechSynthesis/);
});

test("builder normalises common text and structured generation response shapes", () => {
  assert.match(builder, /function normaliseGeneration\(data\)/);
  assert.match(builder, /data\.choices && data\.choices\[0\]/);
  assert.match(builder, /Array\.isArray\(item\.files\)/);
  assert.match(builder, /data && data\.output/);
});

test("builder can recover files when the model omits paths after language fences", () => {
  assert.match(builder, /function extractFiles\(answer, appType\)/);
  assert.match(builder, /defaults\[language\]/);
  assert.match(builder, /app\/src\/main\/AndroidManifest\.xml/);
  assert.match(builder, /client\/index\.html/);
  assert.match(builder, /response-first output|SOURCE-FIRST OUTPUT/);
});

test("builder uses the shared response normalizer in its live request path", () => {
  assert.match(builder, /const answer = normaliseGeneration\(data\)/);
  assert.match(builder, /deterministic evidence synthesis|generation services failed/);
  assert.match(builder, /research fallback instead of generated source code/);
});

test("dedicated builder sends the App Builder mode to FreeApp-AI", () => {
  assert.match(builder, /mode: "app-builder"/);
});


test("live benchmark explicitly enforces Workers-AI-only routing and rejects fallback providers", () => {
  assert.match(fs.readFileSync("tests/app-builder-benchmark.cjs", "utf8"), /providerPolicy: "workers-ai-only"/);
  assert.match(fs.readFileSync("tests/app-builder-benchmark.cjs", "utf8"), /checks\.workers_ai_policy = payload\.providerPolicy === "workers-ai-only"/);
  assert.match(fs.readFileSync("tests/app-builder-benchmark.cjs", "utf8"), /checks\.workers_ai_provider = payload\.modelProvider === "Cloudflare Workers AI"/);
  assert.match(fs.readFileSync("tests/app-builder-benchmark.cjs", "utf8"), /checks\.workers_ai_model_reported/);
});
