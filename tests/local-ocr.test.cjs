const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const html = fs.readFileSync("public/index.html", "utf8");
const app = fs.readFileSync("public/app.js", "utf8");

test("Ask.Me loads Tesseract before its application script", () => {
  assert.match(html, /tesseract\.js@5\/dist\/tesseract\.min\.js/);
  assert.ok(html.indexOf("tesseract.js") < html.indexOf('src="app.js"'));
});

test("Ask.Me runs local OCR with confidence and image preprocessing", () => {
  assert.match(app, /async function extractLocalOcr\(files\)/);
  assert.match(app, /Tesseract\.createWorker\("eng"/);
  assert.match(app, /getImageData/);
  assert.match(app, /confidence/);
  assert.match(app, /\[LOW CONFIDENCE/);
  assert.match(app, /worker\.terminate\(\)/);
});

test("Ask.Me sends OCR transcript with image attachments", () => {
  assert.match(app, /ocrText=await extractLocalOcr(imageFiles)/);
  assert.match(app, /JSON.stringify({prompt,images,ocrText})/);
});
