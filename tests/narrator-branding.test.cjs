const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const app = fs.readFileSync("public/app.js", "utf8");
const html = fs.readFileSync("public/index.html", "utf8");
const voiceHtml = fs.readFileSync("public/voice/index.html", "utf8");
const login = fs.readFileSync("public/login.html", "utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
const workflow = fs.readFileSync(".github/workflows/build-apk.yml", "utf8");

test("Ask.Me frontend JavaScript parses", () => {
  assert.doesNotThrow(() => new vm.Script(app, { filename: "public/app.js" }));
});

test("successful AI answer is rendered before isolated optional narration", () => {
  assert.match(app, /resultBox\.textContent = answer;\s*\/\/ Narration is optional/);
  assert.match(app, /try\s*\{\s*speakText\(answer\);\s*\}\s*catch\s*\(narrationError\)/);
  assert.ok(app.indexOf("resultBox.textContent = answer;") < app.indexOf("speakText(answer);"));
  assert.match(app, /Your answer is still available/);
});

test("undefined legacy narrator globals and server narration calls are removed", () => {
  for (const stale of ["narratorDefaults", "narratorVoiceId", "playServerNarration"]) {
    assert.equal(app.includes(stale), false, "unexpected legacy narrator reference: " + stale);
  }
  assert.match(app, /function speakText\(text\)/);
  assert.doesNotMatch(app, /\/api\/narrat(?:ion|e)/i);
});

test("narrator settings are opt-in, guarded, persisted and constrained to 0.30x-1.50x", () => {
  assert.match(app, /NARRATOR_DEFAULT_RATE\s*=\s*0\.90/);
  assert.match(app, /NARRATOR_MIN_RATE\s*=\s*0\.30/);
  assert.match(app, /NARRATOR_MAX_RATE\s*=\s*1\.50/);
  assert.match(app, /askme_narrator_enabled/);
  assert.match(app, /askme_narrator_speed/);
  assert.match(app, /function readNarratorSetting\(key, fallback\)/);
  assert.match(app, /function saveNarratorSetting\(key, value\)/);
  assert.match(html, /id="narrator-enabled" type="checkbox"/);
  assert.match(html, /id="narrator-speed" type="range" min="0\.30" max="1\.50"/);
});

test("voice selection uses only voices exposed by the browser or device", () => {
  assert.match(app, /window\.speechSynthesis\.getVoices\(\)/);
  assert.match(app, /utterance\.voice = selectedVoice/);
  assert.match(app, /window\.SpeechSynthesisUtterance/);
  assert.match(html, /id="narrator-voice"/);
  assert.match(html, /System default voice/);
});

test("narrator settings and playback failures are isolated from the response UI", () => {
  assert.match(app, /catch \(error\) \{\s*console\.warn\("Narrator preference could not be read:/);
  assert.match(app, /catch \(error\) \{\s*console\.warn\("Narrator preference could not be saved:/);
  assert.match(app, /catch \(error\) \{\s*updateNarratorStatus\("Narration is unavailable\. Your answer is still available\."/);
  assert.match(app, /function stopNarration\(\)/);
});

test("Ask.Me branding is used across the web app, private page, voice studio and Android launcher", () => {
  for (const [name, content] of Object.entries({ app, html, voiceHtml, login, manifest, workflow })) {
    assert.doesNotMatch(content, /Aviator AI/i, name + " still contains old product branding");
  }
  assert.match(html, /<title>Ask\.Me<\/title>/);
  assert.match(html, /<h1>Ask\.Me<\/h1>/);
  assert.match(html, /aria-label="Open Ask\.Me Voice Clone Studio"/);
  assert.match(html, /Ask\.Me Voice Clone Studio/);
  assert.match(voiceHtml, /<title>Ask\.Me Voice Studio/);
  assert.match(voiceHtml, /Ask\.Me Voice Studio/);
  assert.match(login, /Continue to Ask\.Me/);
  assert.match(manifest, /android:label="Ask\.Me"/);
  assert.match(workflow, /name: Build Ask\.Me APK/);
  assert.match(workflow, /AskMe_P703_Format_A\.apk/);
});

test("existing FreeApp-AI inference endpoint and OCR flow are preserved", () => {
  assert.match(app, /const API_BASE = "https:\/\/freeapp-ai\.lovesongmelodies\.workers\.dev"/);
  assert.match(app, /extractLocalOcr\(selectedImages\)/);
  assert.match(app, /JSON\.stringify\(\{ prompt, images, ocrText \}\)/);
});
