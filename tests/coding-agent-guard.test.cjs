const { test } = require("node:test");
const assert = require("node:assert/strict");
const guard = require("../public/coding-agent-guard.js");
const inspected = ["public/app.js", "src/engine/core.ts"];
const proposal = files => ({ summary: "Safe update", tests: ["npm test"], notes: ["Review before merge"], files });

test("accepts an inspected source file", () => {
  const result = guard.validateProposal(proposal([{ path: "public/app.js", content: "console.log('ok');" }]), inspected);
  assert.equal(result.ok, true);
  assert.equal(result.files.length, 1);
});
test("accepts a new file inside an inspected source directory", () => {
  assert.equal(guard.validateProposal(proposal([{ path: "src/engine/core.test.ts", content: "assert(true);" }]), inspected).ok, true);
});
test("rejects a new file at repository root", () => {
  assert.equal(guard.validateProposal(proposal([{ path: "surprise.js", content: "bad" }]), inspected).ok, false);
});
test("rejects traversal and secret paths", () => {
  for (const path of ["../worker.js", "src/../worker.js", ".env", "config/secrets.json", "keys/private.pem", "src\\escape.js", "src/\0escape.js"]) assert.equal(guard.safePath(path), false, path);
});
test("rejects duplicate paths", () => {
  const result = guard.validateProposal(proposal([{path:"public/app.js",content:"a"},{path:"public/app.js",content:"b"}]), inspected);
  assert.match(result.message, /same file more than once/);
});
test("rejects binary and generated artifacts", () => {
  for (const path of ["release.apk", "build/output.js", "node_modules/pkg/index.js", "package-lock.json"]) assert.equal(guard.safePath(path), false, path);
});
test("rejects malformed metadata", () => {
  const bad = proposal([{path:"public/app.js",content:"ok"}]); bad.tests = [42];
  assert.equal(guard.validateProposal(bad, inspected).ok, false);
});
test("rejects too many files and oversized file content", () => {
  const many = Array.from({length:13},(_,i)=>({path:"public/f"+i+".js",content:"x"}));
  assert.equal(guard.validateProposal(proposal(many), inspected).ok, false);
  assert.equal(guard.validateProposal(proposal([{path:"public/app.js",content:"x".repeat(20001)}]), inspected).ok, false);
});
test("returns explicit no-change state for empty proposal", () => {
  const result = guard.validateProposal(proposal([]), inspected);
  assert.equal(result.ok, false); assert.equal(result.noChanges, true);
});
test("creates eight-character hexadecimal branch suffixes", () => {
  assert.match(guard.randomSuffix(), /^[a-f0-9]{8}$/);
});

test("accepts deletion only for an inspected existing file", () => {
  const result = guard.validateProposal(proposal([{ path: "public/app.js", operation: "delete" }]), inspected);
  assert.equal(result.ok, true);
  assert.deepEqual(result.files[0], { path: "public/app.js", operation: "delete" });
});
test("rejects deletion of an uninspected file and delete content", () => {
  assert.equal(guard.validateProposal(proposal([{ path: "public/new.js", operation: "delete" }]), inspected).ok, false);
  assert.equal(guard.validateProposal(proposal([{ path: "public/app.js", operation: "delete", content: "" }]), inspected).ok, false);
});
test("allows common build files and blocks workflows unless explicitly requested", () => {
  assert.equal(guard.safePath("Dockerfile"), true);
  assert.equal(guard.validateProposal(proposal([{ path: "Dockerfile", content: "FROM scratch" }]), ["package.json"]).ok, true);
  assert.equal(guard.safePath(".gitignore"), true);
  assert.equal(guard.safePath(".github/workflows/deploy.yml"), false);
  assert.equal(guard.safePath(".github/workflows/deploy.yml", { allowWorkflowChanges: true }), true);
});
