const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
const match = source.match(/function selectIndependentResearchSources\([\s\S]*?\n\}/);
assert.ok(match, "independent source selector must exist");
const select = vm.runInNewContext(match[0] + "\nselectIndependentResearchSources");

test("research selection prefers distinct domains before duplicate publishers", () => {
  const items = [
    { host: "same.example", relevant: [{ text: "a" }] },
    { host: "same.example", relevant: [{ text: "b" }] },
    { host: "other.example", relevant: [{ text: "c" }] },
    { host: "third.example", relevant: [{ text: "d" }] }
  ];
  const result = select(items, 4, 2);
  assert.deepEqual(Array.from(result, x => x.host), [
    "same.example", "other.example", "third.example", "same.example"
  ]);
});

test("research keeps readable pages as context-only sources without treating them as evidence", () => {
  const items = [
    { host: "empty.example", relevant: [] },
    { host: "usable.example", relevant: [{ text: "matching evidence" }] }
  ];
  const result = Array.from(select(items, 8, 2));
  assert.deepEqual(result.map(x => x.host), ["usable.example", "empty.example"]);
  assert.equal(result[1].contextOnly, true);
  assert.equal(Array.from(result[1].relevant).length, 0);
});

test("research selection respects per-domain and result limits", () => {
  const items = Array.from({ length: 6 }, (_, i) => ({
    host: "publisher.example",
    relevant: [{ text: String(i) }]
  }));
  const result = select(items, 8, 2);
  assert.equal(result.length, 2);
});
