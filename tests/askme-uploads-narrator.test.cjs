const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const app = fs.readFileSync("public/app.js", "utf8");
const html = fs.readFileSync("public/index.html", "utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
const activity = fs.readFileSync("app/src/main/java/ai/freeapp/app/MainActivity.java", "utf8");

test("Ask.Me exposes multi-format file selection", () => {
  assert.match(html, /id="file-input"/);
  assert.match(html, /image\/\*,video\/\*/);
  assert.match(html, /\.pdf/);
  assert.match(html, /\.docx/);
});
test("documents are extracted locally before generation", () => {
  assert.match(html, /pdf\.js/);
  assert.match(html, /mammoth\.browser/);
  assert.match(app, /extractPdfText/);
  assert.match(app, /extractDocxText/);
  assert.match(app, /file\.text\(\)/);
});
test("video attachments are sampled into visual frames", () => {
  assert.match(app, /extractVideoFrames/);
  assert.match(app, /seekVideo/);
  assert.match(app, /MAX_VIDEO_SECONDS/);
});
test("narrator is visible, opt-in, adjustable and independent", () => {
  assert.match(html, /id="narrator-enabled"/);
  assert.match(html, /min="0\.30" max="1\.50"/);
  assert.match(app, /utterance\.rate=Number\(narratorSpeed\.value\)/);
  assert.match(app, /speakAnswer\(answer\)/);
  assert.match(app, /Narration is isolated/);
});
test("Android WebView supports native file chooser and multiple selected URIs", () => {
  assert.match(activity, /onShowFileChooser/);
  assert.match(activity, /getClipData\(\)/);
  assert.match(activity, /pendingFileCallback\.onReceiveValue/);
});
test("Ask.Me branding is used in the app and launcher", () => {
  assert.match(html, /<title>Ask\.Me<\/title>/);
  assert.match(html, /<h1>Ask\.Me<\/h1>/);
  assert.match(manifest, /android:label="Ask\.Me"/);
});
