const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
const match = source.match(/function engineeringDeliverableGaps\([\s\S]*?\n\}/);
assert.ok(match, "engineering deliverable validator must exist");
const gapsFor = vm.runInNewContext(match[0] + "\nengineeringDeliverableGaps");
const fence = String.fromCharCode(96).repeat(3);

test("programming deliverable validation detects missing executable code, tests and complexity", () => {
  const gaps = Array.from(gapsFor(
    "Implement graph cycle detection algorithm with API input validation and tests",
    "Here is the approach. It should work."
  ));
  assert.ok(gaps.some(x => /fenced code/.test(x)));
  assert.ok(gaps.some(x => /tests with concrete assertions/.test(x)));
  assert.ok(gaps.some(x => /complexity analysis/.test(x)));
  assert.ok(gaps.some(x => /input and unknown-endpoint validation/.test(x)));
});

test("complete programming deliverable passes relevant checks", () => {
  const answer = [
    fence + "js",
    "function solve(input) { if (!Array.isArray(input)) throw new Error('invalid input'); return input.length; }",
    fence,
    "Tests: assert.equal(solve([]), 0). Expected output: 0.",
    "Time complexity O(n); space complexity O(1).",
    "API validation rejects invalid input with HTTP 400."
  ].join("\n");
  assert.deepEqual(Array.from(gapsFor("Implement API algorithm with tests and complexity analysis", answer)), []);
});

test("app-building validation checks schema, transactions, offline sync and build steps", () => {
  const gaps = Array.from(gapsFor(
    "Build an app with database schema, stock transactions, offline sync, and eight acceptance tests",
    "Architecture and data flow are described. Build and run with npm run build.",
    true
  ));
  assert.ok(gaps.some(x => /database schema/.test(x)));
  assert.ok(gaps.some(x => /atomic transaction/.test(x)));
  assert.ok(gaps.some(x => /offline behavior/.test(x)));
  assert.ok(gaps.some(x => /eight individually numbered/.test(x)));
});

test("app-building validation accepts a complete relevant deliverable", () => {
  const answer = [
    fence + "js",
    "export function saveStock() { return true; }",
    fence,
    "Architecture and data flow: inventory module calls the database.\nRequirement-to-module map: stock tracking -> inventory module; persistence -> database module.",
    "Database schema: stock(id PRIMARY KEY, quantity CHECK(quantity >= 0)).",
    "Atomic transaction with rollback and idempotency key prevents duplicate stock changes.",
    "Offline queue and sync reconcile conflicts with retry.",
    ...Array.from({length:8},(_,i)=>"Acceptance test "+(i+1)+": setup, action and expected result."),
    "Build and run with npm run build."
  ].join("\n");
  assert.deepEqual(Array.from(gapsFor("Build app with database stock transactions offline sync and eight acceptance tests", answer, true)), []);
});


test("engineering planning remains enabled for complex work even when research retrieves no usable sources", () => {
  const worker = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
  assert.match(worker, /if \(shouldUseEngineeringPlanner\(prompt, buildMode\)\)/);
  assert.match(worker, /function shouldUseEngineeringPlanner\(prompt, buildMode = false\)/);
  assert.match(worker, /if \(buildMode\) return true/);
  assert.match(worker, /still produce a sound plan from the task/);
});


test("programming completeness gate requires test outcomes, not a passing mention of the word test", () => {
  const gaps = Array.from(gapsFor(
    "Implement a sorting function",
    fence + "js\nfunction sortValues(values) { return [...values].sort((a,b) => a-b); }\n" + fence + "\nThis test plan covers the function."
  ));
  assert.ok(gaps.some(x => /assertions or expected outcomes/.test(x)));
});

test("app-building completeness gate requires a requirement-to-module coverage map", () => {
  const answer = [
    fence + "js", "export function saveStock() { return true; }", fence,
    "Architecture and data flow: inventory module writes to SQLite.",
    "Database schema: stock(id PRIMARY KEY, quantity CHECK(quantity >= 0)).",
    "Atomic transaction with rollback and idempotency key.",
    "Offline queue and sync reconcile conflicts with retry.",
    ...Array.from({length:8},(_,i)=>"Acceptance test "+(i+1)+": setup, action and expected result."),
    "Build and run with npm run build."
  ].join("\n");
  const gaps = Array.from(gapsFor("Build app with database stock transactions offline sync and eight acceptance tests", answer, true));
  assert.ok(gaps.some(x => /requirement-to-module/.test(x)));
});
