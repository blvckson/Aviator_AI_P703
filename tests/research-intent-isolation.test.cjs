const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
const match = source.match(/function isResearchFirstRequest\([\s\S]*?\n\}/);
assert.ok(match, "research intent classifier must exist");
const classify = vm.runInNewContext(match[0] + "\nisResearchFirstRequest");

test("research about software development is not misrouted as coding", () => {
  assert.equal(classify("Research the current official AI model options relevant to reasoning and software development."), true);
});

test("research comparisons remain research even when they mention APIs and models", () => {
  assert.equal(classify("Compare current API model options, evaluate evidence, and recommend the best model for coding."), true);
});

test("explicit code generation takes precedence over research wording", () => {
  assert.equal(classify("Research the problem, then write a Python program that implements the solution."), false);
});

test("explicit app creation is not classified as research-only", () => {
  assert.equal(classify("Research options and build an Android app with offline storage."), false);
});

test("ordinary non-research coding is not classified as research", () => {
  assert.equal(classify("Write a JavaScript function to sort records."), false);
});
