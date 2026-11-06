const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "..", "worker.js"), "utf8");
const match = source.match(/function shouldUseEngineeringPlanner\(prompt, buildMode = false\) \{[\s\S]*?\n\}/);
assert.ok(match, "planner scope helper must exist");
const shouldUseEngineeringPlanner = new Function("return (" + match[0] + ");")();

test("focused code generation skips planning while debugging and repairs retain it", () => {
  assert.equal(shouldUseEngineeringPlanner("Write a Python function first_non_repeating(s)."), false);
  assert.equal(shouldUseEngineeringPlanner("Implement a small algorithm."), false);
  assert.equal(shouldUseEngineeringPlanner("Debug this JavaScript function."), true);
  assert.equal(shouldUseEngineeringPlanner("Fix this compile error."), true);
  assert.equal(shouldUseEngineeringPlanner("Refactor this class."), true);
});

test("complex application and repository work retain the planning inference", () => {
  assert.equal(shouldUseEngineeringPlanner("Build a full application with several modules."), true);
  assert.equal(shouldUseEngineeringPlanner("Inspect the whole repository and implement the deployment pipeline."), true);
  assert.equal(shouldUseEngineeringPlanner("A complex app build request.", true), true);
});

test("planner gate is applied only to the engineering planning stage", () => {
  assert.match(source, /if \(shouldUseEngineeringPlanner\(prompt, buildMode\)\)/);
  assert.match(source, /if \(responseText && !modelIndependentFallback && complexity\.complex && \(buildMode \|\| codeTask \|\| researchMode\)\)/);
});
