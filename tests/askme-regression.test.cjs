const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const app = fs.readFileSync("public/app.js", "utf8");
const html = fs.readFileSync("public/index.html", "utf8");
const activity = fs.readFileSync("app/src/main/java/ai/freeapp/app/MainActivity.java", "utf8");

test("Ask.Me frontend JavaScript parses", () => {
  assert.doesNotThrow(() => new vm.Script(app, { filename: "public/app.js" }));
});

test("narrator runtime dependencies are defined and stale names are gone", () => {
  for (const name of ["speakText", "stopNarrator", "speakNextChunk", "getNarratorRate"]) {
    assert.match(app, new RegExp("function\\s+" + name + "\\s*\\("));
  }
  for (const stale of ["narratorDefaults", "narratorVoiceId", "playServerNarration"]) {
    assert.equal(app.includes(stale), false, "unexpected stale narrator reference: " + stale);
  }
});

test("narrator defaults to 0.90x and supports 0.70x to 1.50x speed", () => {
  assert.match(app, /DEFAULT_NARRATOR_RATE\s*=\s*0\.90/);
  assert.match(html, /id="narrator-speed"[^>]*min="0\.70"[^>]*max="1\.50"/);
  assert.match(app, /utterance\.rate\s*=\s*getNarratorRate\(\)/);
});

test("native Android TTS bridge and browser fallback are present", () => {
  assert.match(activity, /addJavascriptInterface\(new NativeNarrator\(\), "AskMeNativeTTS"\)/);
  assert.match(activity, /@JavascriptInterface/);
  assert.match(activity, /textToSpeech\.shutdown\(\)/);
  assert.match(activity, /shouldOverrideUrlLoading/);
  assert.match(activity, /appassets\.androidplatform\.net/);
  assert.match(app, /window\.AskMeNativeTTS\.speakChunk/);
  assert.match(app, /window\.speechSynthesis\.speak/);
});

test("automatic narration is opt-in and persisted", () => {
  assert.match(html, /id="narrator-auto-read"/);
  assert.match(app, /askme_narrator_auto_read/);
  assert.match(app, /narratorAutoRead\.checked\)\s*speakText/);
});

test("AI requests handle empty responses, timeouts, and always release the submit button", () => {
  assert.match(app, /REQUEST_TIMEOUT_MS\s*=\s*120000/);
  assert.match(app, /if \(!answer\.trim\(\)\) throw new Error/);
  assert.match(app, /finally\s*\{/);
  assert.match(app, /buildButton\.disabled\s*=\s*false/);
});

test("research links and media URLs are validated before rendering", () => {
  assert.match(app, /source\.url/);
  assert.match(app, /item\.url/);
  assert.match(app, /noopener noreferrer/);
});

test("quality additions target research and engineering while preserving reasoning and debugging paths", () => {
  assert.match(app, /function buildQualityPrompt\\(userPrompt\\)/);
  assert.match(app, /Research integrity:/);
  assert.match(app, /Engineering quality:/);
  assert.match(app, /Graph\\/code self-audit:/);
  assert.match(app, /Accessibility self-audit:/);
  assert.match(app, /Repository-operation honesty:/);
  assert.doesNotMatch(app, /Debugging quality:/);
  assert.doesNotMatch(app, /Reasoning quality:/);
  assert.match(app, /prompt: buildQualityPrompt\\(effectivePrompt\\)/);
});

test("research intent is not reported as verified without returned source metadata", () => {
  assert.match(app, /const hasVerifiedSources = data\.researched === true/);
  assert.match(app, /live source verification was not confirmed/);
});

test("engineering prompt adds explicit graph endpoint and UI accessibility self-audits", () => {
  assert.match(app, /Graph\/code self-audit:/);
  assert.match(app, /validate BOTH endpoints/);
  assert.match(app, /Accessibility self-audit:/);
  assert.match(app, /aria-labelledby/);
});

test("document and source-code upload picker is wired into prompt context", () => {
  assert.match(html, /id="file-input"[^>]*multiple/);
  assert.match(html, /\.txt,\.md,\.csv,\.json/);
  assert.match(app, /selectedDocs/);
  assert.match(app, /function toText\(file\)/);
  assert.match(app, /ATTACHED FILE:/);
  assert.match(app, /MAX_DOC_BYTES/);
});

test("Android file chooser respects accepted MIME types and multiple selection", () => {
  assert.match(activity, /fileChooserParams\.getAcceptTypes\(\)/);
  assert.match(activity, /EXTRA_MIME_TYPES/);
  assert.match(activity, /MODE_OPEN_MULTIPLE/);
  assert.match(activity, /getClipData\(\)/);
});

test("image-only prompts are converted into a valid non-empty API request", () => {
  assert.match(app, /Please analyze the attached image/);
  assert.match(app, /images\.length \?/);
});
