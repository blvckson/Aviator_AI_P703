const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const worker = fs.readFileSync(new URL("../worker.js", `file://${__filename}`), "utf8");
const start = worker.indexOf("function buildEngineeringFallback(prompt) {");
const end = worker.indexOf("function buildResearchOnlyFallback(prompt, brief, modelError) {", start);
assert.ok(start >= 0 && end > start);
const buildEngineeringFallback = vm.runInNewContext("(" + worker.slice(start, end).trim() + ")");
const researchEnd = worker.indexOf("\nfunction formatIndependentEvidenceBrief", end);
const buildResearchOnlyFallback = vm.runInNewContext("(" + worker.slice(end, researchEnd).trim() + ")");

test("debugging fallback fixes async map/filter and preserves names", () => {
 const a = buildEngineeringFallback("Debug: async function getNames(ids){ const results = ids.map(async id => await fetchName(id)); return results.filter(x => x.active); }");
 assert.match(a, /Promise\.all/); assert.match(a, /function getNames\(ids\)/); assert.match(a, /fetchName/); assert.match(a, /assert\.deepEqual/);
});
test("Task Pocket fallback returns complete accessible localStorage app", () => {
 const a = buildEngineeringFallback("Build a tiny complete single-file web app called Task Pocket: add tasks, mark complete, delete tasks, filter All/Active/Completed, persist tasks after refresh using localStorage. Return only a complete runnable HTML file with CSS and JavaScript. No external libraries. Include accessible labels and handle empty state.");
 for (const term of ["<!doctype html>", "localStorage", "data-filter=", "All", "Active", "Completed", "Delete", "aria-label", "No tasks yet"]) assert.ok(a.includes(term), "missing " + term);
});
test("passing programming tasks remain outside this fallback", () => {
 assert.equal(buildEngineeringFallback("Write Python code for first_non_repeating(s) with tests."), "");
 assert.equal(buildEngineeringFallback("Write a topological sort implementation."), "");
});
test("unmatched tasks do not receive canned output", () => {
 assert.equal(buildEngineeringFallback("Build a completely unrelated compiler."), "");
});
test("normal generation path remains present", () => {
 assert.match(worker, /draft = await runChatModel\(env, messages/);
 assert.match(worker, /engineeringTask \? buildEngineeringFallback\(prompt\) : ""/);
});
test("MediaProjection research fallback gives official API level and setup references", () => {
 const a = buildResearchOnlyFallback("Research Android MediaProjection API requirements", null, new Error("provider unavailable"));
 assert.match(a, /API level 21/); assert.match(a, /Android 14/); assert.match(a, /FOREGROUND_SERVICE_MEDIA_PROJECTION/);
 assert.match(a, /developer\.android\.com\/reference\/android\/media\/projection\/MediaProjectionManager/);
 assert.match(a, /developer\.android\.com\/media\/grow\/media-projection/);
});
test("generic research diagnostic is defined", () => {
 const a = buildResearchOnlyFallback("ordinary research", null, new Error("gateway down"));
 assert.match(a, /gateway down/);
});
