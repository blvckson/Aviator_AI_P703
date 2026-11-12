const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

const app = fs.readFileSync("public/app.js", "utf8");
const html = fs.readFileSync("public/index.html", "utf8");
const voiceHtml = fs.readFileSync("public/voice/index.html", "utf8");
const loginHtml = fs.readFileSync("public/login.html", "utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");

test("AI answer is rendered before optional narration is attempted", () => {
  assert.ok(app.indexOf("resultBox.textContent = answerText") < app.indexOf("readAnswerAloud(answerText)"));
  assert.match(app, /if \(narratorEnabled && answerText\)/);
  assert.match(app, /narrator failures must never discard an AI answer/);
});

test("obsolete undefined narrator globals are not referenced", () => {
  assert.doesNotMatch(app, /narratorDefaults|narratorVoiceId|playServerNarration/);
});

test("read-aloud is optional and its preference is persisted", () => {
  assert.match(html, /id="narrator-enabled"/);
  assert.match(app, /askme_narrator_enabled/);
  assert.match(app, /SpeechSynthesisUtterance/);
});

test("Ask.Me branding is consistent across web, voice studio, login, and Android launcher", () => {
  assert.match(html, /<title>Ask\.Me<\/title>/);
  assert.match(html, /<h1>Ask\.Me<\/h1>/);
  assert.match(voiceHtml, /Ask\.Me Voice Studio/);
  assert.doesNotMatch(loginHtml, /Aviator AI/);
  assert.match(manifest, /android:label="Ask\.Me"/);
});
