const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

test("programming generation gets the extended timeout classifier only", () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
  const focused = source.match(/function isFocusedCodeGenerationRequest\(messages\) \{[\s\S]*?\n\}/);
  const declaration = source.match(/function isProgrammingGenerationRequest\(messages\) \{[\s\S]*?\n\}/);
  assert.ok(declaration, "programming request classifier must exist");
  assert.ok(focused && declaration, "programming classifiers must exist");
  const classify = new Function(focused[0] + "\n" + declaration[0] + "\nreturn isProgrammingGenerationRequest;")();

  assert.equal(classify([{ role: "user", content: "Write a Python function and include unit tests." }]), true);
  assert.equal(classify([{ role: "user", content: "Implement the algorithm in JavaScript." }]), true);
  assert.equal(classify([{ role: "user", content: "Explain the causes of inflation." }]), false);
  assert.equal(classify([{ role: "user", content: "Compare two historical events." }]), false);
  assert.equal(classify([{ role: "user", content: "Build a full Android app." }]), false);

  assert.match(source, /providerTimeoutMs: isProgrammingGenerationRequest\(messages\) \? 22000 : 10000/);
  assert.match(source, /Number\(settings\.providerTimeoutMs \|\| provider\.timeoutMs\)/);
});

test("programming requests do not repeat a full network timeout", () => {
  const source = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
  assert.match(source, /retryNetworkTimeouts: !isProgrammingGenerationRequest\(messages\)/);
  assert.match(source, /const timedOut = \/timeout\|timed out\|aborted\/i\.test/);
  assert.match(source, /!timedOut && settings\.retryNetworkTimeouts !== false/);
  assert.match(source, /status === 408 \|\| status === 425 \|\| status >= 500/);
});
