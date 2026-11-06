const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const worker = fs.readFileSync(new URL("../worker.js", `file://${__filename}`), "utf8");
const deliverableStart = worker.indexOf("function engineeringDeliverableGaps(");
const deliverableEnd = worker.indexOf("\nfunction isSoftwareEngineeringRequest", deliverableStart);
assert.ok(deliverableStart >= 0 && deliverableEnd > deliverableStart);
const gapsFor = vm.runInNewContext("(" + worker.slice(deliverableStart, deliverableEnd).trim() + ")");
const researchStart = worker.indexOf("function selectIndependentResearchSources(");
const researchEnd = worker.indexOf("\nfunction buildIndependentEvidenceBrief", researchStart);
assert.ok(researchStart >= 0 && researchEnd > researchStart);
const selectSources = vm.runInNewContext("(" + worker.slice(researchStart, researchEnd).trim() + ")");
const fence = "```";

test("programming deliverables retain baseline test coverage even when tests were not named in the prompt", () => {
  const gaps = Array.from(gapsFor(
    "Write a JavaScript function that sorts records by date.",
    fence + "js\nfunction sortRecords(records) { return records.slice().sort((a,b) => a.date-b.date); }\n" + fence
  ));
  assert.ok(gaps.some(g => /meaningful tests paired with concrete assertions or expected outcomes/.test(g)));
});

test("programming deliverables pass the restored baseline when tests and expected results are present", () => {
  const answer = fence + "js\nfunction add(a,b) { return a+b; }\n" + fence +
    "\nTest: assert.equal(add(2, 3), 5). Expected output: 5.";
  assert.deepEqual(Array.from(gapsFor("Write a JavaScript add function.", answer)), []);
});

test("explicit test requests still require concrete assertions", () => {
  const answer = fence + "js\nfunction add(a,b) { return a+b; }\n" + fence +
    "\nThis includes tests for normal and boundary cases.";
  const gaps = Array.from(gapsFor("Write an add function with tests.", answer));
  assert.ok(gaps.some(g => /concrete assertions and expected outcomes/.test(g)));
});

test("research source selection restores context-only coverage when strict sentence matching yields too few sources", () => {
  const selected = Array.from(selectSources([
    { host: "official.example", title: "Official docs", url: "https://official.example/docs", score: 10, relevant: [{sentence:"A directly relevant fact.",hits:1}] },
    { host: "independent.example", title: "Independent guide", url: "https://independent.example/guide", score: 8, relevant: [] }
  ], 8, 2));
  assert.equal(selected.length, 2);
  assert.equal(selected[0].host, "official.example");
  assert.equal(selected[1].host, "independent.example");
  assert.equal(selected[1].contextOnly, true);
  assert.equal(selected[1].relevant.length, 0);
});

test("research source selection does not label context-only pages as evidence claims", () => {
  const selected = Array.from(selectSources([
    { host: "one.example", title: "One", url: "https://one.example", score: 9, relevant: [] },
    { host: "two.example", title: "Two", url: "https://two.example", score: 8, relevant: [] }
  ], 8, 2));
  assert.ok(selected.every(item => item.contextOnly === true));
  assert.ok(selected.every(item => item.relevant.length === 0));
});


test("research restores broad source-register coverage without promoting context-only pages to evidence", () => {
  const selected = Array.from(selectSources([
    { host: "evidence.example", title: "Evidence", url: "https://evidence.example", score: 10, relevant: [{sentence:"A relevant verified sentence.",hits:2}] },
    { host: "context-a.example", title: "Context A", url: "https://context-a.example", score: 9, relevant: [] },
    { host: "context-b.example", title: "Context B", url: "https://context-b.example", score: 8, relevant: [] },
    { host: "context-c.example", title: "Context C", url: "https://context-c.example", score: 7, relevant: [] }
  ], 4, 2));
  assert.equal(selected.length, 4);
  assert.equal(selected[0].host, "evidence.example");
  assert.ok(selected.slice(1).every(item => item.contextOnly === true && item.relevant.length === 0));
});

test("app-building validator accepts eight commonly numbered acceptance tests", () => {
  const answer = fence + "kotlin\nfun saveStock() = true\n" + fence + "\n" +
    "Architecture and data flow: inventory module writes to SQLite.\n" +
    "Database schema: stock table with primary key and quantity constraint.\n" +
    "Atomic transaction with rollback and idempotency key.\n" +
    "Offline sync queues changes and resolves conflicts.\n" +
    "Requirement-to-module map: inventory -> stock module; persistence -> database module.\n" +
    Array.from({length:8}, (_, i) => "Test " + (i + 1) + ": verify the expected stock quantity after the operation.").join("\n") +
    "\nBuild and run with ./gradlew assembleDebug.\nassert expected stock count.";
  assert.deepEqual(Array.from(gapsFor("Build an inventory app with database, stock transactions, offline sync and at least eight acceptance tests", answer, true)), []);
});
