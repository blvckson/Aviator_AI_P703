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
  assert.match(builder, /complete, production-minded source files/);
  assert.match(builder, /automated tests and exact local build\/run commands/);
  assert.match(builder, /Never claim a build passed unless it was actually built/);
  assert.match(builder, /Do not say code was compiled, tested, installed, or deployed unless the operation actually happened/);
});

test("builder extracts safe labelled files and offers downloads", () => {
  assert.match(builder, /function extractFiles\(answer\)/);
  assert.match(builder, /path\.startsWith\("\/"\)/);
  assert.match(builder, /split\(\/[\\\\/]\/\)/);
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
