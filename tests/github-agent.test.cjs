const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const agent = fs.readFileSync("public/github-agent.js", "utf8");
const html = fs.readFileSync("public/index.html", "utf8");
const guard = fs.readFileSync("public/coding-agent-guard.js", "utf8");

test("GitHub agent JavaScript parses", () => {
  assert.doesNotThrow(() => new vm.Script(agent, { filename: "public/github-agent.js" }));
});

test("GitHub agent UI exposes repository, token, task and operation controls", () => {
  for (const id of ["github-repo","github-token","github-coding-task","github-coding-run","github-coding-clear","github-coding-workflow","github-coding-build","github-coding-check","github-coding-repair","github-coding-deploy"]) {
    assert.ok(html.includes('id="' + id + '"'), "missing UI control " + id);
  }
  assert.match(html, /id="github-token" type="password"/);
  assert.match(html, /Contents read\/write, Pull requests read\/write, and Actions read\/write/);
});

test("GitHub token is not persisted or sent to the AI generation endpoint", () => {
  assert.match(agent, /Authorization":\s*"Bearer "\s*\+\s*token/);
  assert.match(agent, /fetch\(API_BASE\s*\+\s*"\/api\/generate"/);
  assert.doesNotMatch(agent, /localStorage\.setItem\([^)]*token/i);
  const start = agent.indexOf('fetch(API_BASE + "/api/generate"');
  const aiRequest = agent.slice(start, start + 1200);
  assert.doesNotMatch(aiRequest, /Authorization|github-token|tokenInput/);
});

test("agent creates feature branch, atomic commit and pull request", () => {
  assert.match(agent, /createBranch\(/);
  assert.match(agent, /commitFilesAtomically\(/);
  assert.match(agent, /"\/repos\/" \+ repo \+ "\/pulls"/);
  assert.match(agent, /askme-ai\//);
});

test("agent dispatches builds, checks all exact-commit branch workflows and uses actual failure logs for repairs", () => {
  assert.match(agent, /dispatchBuildWorkflow/);
  assert.match(agent, /monitorPullRequestChecks/);
  assert.match(agent, /run\.head_sha === commitSha && run\.head_branch === branch/);
  assert.doesNotMatch(agent, /run\.pull_requests \|\| \[\]\)\.some/);
  assert.match(agent, /failure_log_excerpts/);
  assert.match(agent, /repairFailedPullRequest/);
  assert.match(agent, /\/actions\/jobs\/" \+ job\.id \+ "\/logs/);
});

test("production deployment is gated by merged PR, default branch, successful checks and confirmation", () => {
  assert.match(agent, /if \(!pr\.merged_at\)/);
  assert.match(agent, /repository\.default_branch/);
  assert.match(agent, /check\.conclusion !== "success"/);
  assert.match(agent, /window\.confirm\("Deploy the VERIFIED default branch/);
});

test("guard blocks traversal, secrets and unrequested workflow edits", () => {
  assert.match(guard, /path\.includes/);
  assert.match(guard, /\.env/);
  assert.match(guard, /allowWorkflowChanges !== true/);
  assert.match(guard, /MAX_FILES/);
});
