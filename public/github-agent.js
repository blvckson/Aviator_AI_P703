// Prompt-driven GitHub coding agent. GitHub credentials stay in this page and
// are sent only to api.github.com; the AI receives source code, never the token.
(() => {
  const repoInput = document.getElementById("github-repo");
  const tokenInput = document.getElementById("github-token");
  const taskInput = document.getElementById("github-coding-task");
  const runButton = document.getElementById("github-coding-run");
  const clearButton = document.getElementById("github-coding-clear");
  const status = document.getElementById("github-coding-status");
  const result = document.getElementById("github-coding-result");
  if (!repoInput || !tokenInput || !taskInput || !runButton || !status || !result) return;

  const ghHeaders = token => ({
    "Accept": "application/vnd.github+json",
    "Authorization": "Bearer " + token,
    "X-GitHub-Api-Version": "2022-11-28"
  });
  async function gh(token, path, options = {}) {
    const response = await fetch("https://api.github.com" + path, {
      ...options,
      headers: { ...ghHeaders(token), ...(options.headers || {}) }
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      const message = data.message || ("GitHub API returned HTTP " + response.status);
      if (response.status === 401) throw new Error("GitHub token is invalid or expired. Create a fine-grained token with repository Contents and Pull requests read/write permissions.");
      if (response.status === 403) throw new Error("GitHub denied access. Check token permissions and repository access. " + message);
      throw new Error(message);
    }
    return data;
  }
  function decodeBase64Utf8(value) {
    const binary = atob(String(value || "").replace(/\n/g, ""));
    const bytes = Uint8Array.from(binary, ch => ch.charCodeAt(0));
    return new TextDecoder("utf-8").decode(bytes);
  }
  function encodeBase64Utf8(value) {
    const bytes = new TextEncoder().encode(value);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  }
  function safePath(path) {
    return typeof path === "string" && path.length > 0 && !path.startsWith("/") &&
      !path.split("/").includes("..") && !path.includes("\\") &&
      !/(^|\/)(\.git|secrets?|\.env)(\/|$)/i.test(path) && !/\.(pem|key|p12|keystore)$/i.test(path);
  }
  function relevantFiles(tree, task) {
    const words = [...new Set((task.toLowerCase().match(/[a-z0-9_./-]{3,}/g) || [])
      .filter(w => !["the","and","for","with","that","this","from","into","fix","add","make","please","code","file"].includes(w)))];
    const sourceExt = /\.(js|mjs|ts|tsx|jsx|html|css|kt|java|gradle|kts|xml|json|py|go|rs|swift|dart|yml|yaml|md)$/i;
    const files = tree.filter(x => x.type === "blob" && safePath(x.path) && sourceExt.test(x.path) &&
      !/(^|\/)(build|node_modules|\.gradle|\.git|dist|vendor|\.idea)(\/|$)/i.test(x.path) &&
      !/lock\.(json|yaml|yml)$|\.min\.js$|\.wasm$|\.png$|\.jpg$|\.jpeg$|\.onnx$/i.test(x.path));
    return files.map(file => {
      const path = file.path.toLowerCase();
      let score = 0;
      for (const word of words) if (path.includes(word)) score += 4;
      if (/(readme|package\.json|build\.gradle|settings\.gradle|manifest|app\.js|worker\.js|index\.html)$/i.test(path)) score += 1;
      if (path.split("/").length <= 2) score += 1;
      return { ...file, score };
    }).sort((a,b) => b.score-a.score || a.path.localeCompare(b.path)).slice(0, 60);
  }
  async function getFile(token, repo, path, ref) {
    const data = await gh(token, "/repos/" + repo + "/contents/" + path.split("/").map(encodeURIComponent).join("/") + "?ref=" + encodeURIComponent(ref));
    if (Array.isArray(data) || data.type !== "file" || !data.content) return null;
    return { path, sha: data.sha, content: decodeBase64Utf8(data.content) };
  }
  function extractJson(text) {
    const raw = String(text || "").trim().replace(/^\x60{3}(?:json)?\s*/i, "").replace(/\s*\x60{3}$/i, "");
    const first = raw.indexOf("{"), last = raw.lastIndexOf("}");
    if (first < 0 || last <= first) throw new Error("The AI did not return a valid JSON code-change proposal. No files were changed.");
    try { return JSON.parse(raw.slice(first, last + 1)); }
    catch { throw new Error("The AI code proposal was not valid JSON. No files were changed."); }
  }
  async function askForPatch(prompt, files, treePaths) {
    const source = files.map(f => "\n--- FILE: " + f.path + " ---\n" + f.content).join("\n").slice(0, 36000);
    const instruction = [
      "You are Ask.Me AI acting as a careful software engineer modifying an existing GitHub repository.",
      "Return ONLY valid JSON with this schema: {\"summary\":\"short summary\",\"files\":[{\"path\":\"existing/path or new/path\",\"content\":\"complete UTF-8 file contents\"}],\"tests\":[\"test or build command\"],\"notes\":[\"important limitations\"]}. For a deletion, use {\"path\":\"existing/path\",\"operation\":\"delete\"} with no content field.\n" + treePaths.slice(0, 80).join("\n"),
      "CURRENT SOURCE FILES:\n" + source,
      "USER'S REQUEST:\n" + prompt
    ].join("\n\n");
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 90000);
    let response;
    try {
      response = await fetch(API_BASE + "/api/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: instruction, images: [], history: [], memorySummary: "" }),
        signal: controller.signal
      });
    } catch (error) {
      if (error && error.name === "AbortError") throw new Error("Code generation exceeded 90 seconds. No repository files were changed; your task remains available for retry.");
      throw error;
    } finally {
      clearTimeout(timeout);
    }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error((data.error || "Ask.Me AI could not prepare a code change.") + (data.detail ? " " + data.detail : ""));
    return extractJson(data.result);
  }
  async function createBranch(token, repo, branchName, baseSha) {
    await gh(token, "/repos/" + repo + "/git/refs", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ref: "refs/heads/" + branchName, sha: baseSha })
    });
  }
  async function commitFilesAtomically(token, repo, branch, parentSha, files) {
    const parent = await gh(token, "/repos/" + repo + "/git/commits/" + parentSha);
    const entries = await Promise.all(files.map(async file => {
      if (file.operation === "delete") {
        return { path: file.path, mode: "100644", type: "blob", sha: null };
      }
      const blob = await gh(token, "/repos/" + repo + "/git/blobs", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: file.content, encoding: "utf-8" })
      });
      return { path: file.path, mode: "100644", type: "blob", sha: blob.sha };
    }));
    const tree = await gh(token, "/repos/" + repo + "/git/trees", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ base_tree: parent.tree.sha, tree: entries })
    });
    const commit = await gh(token, "/repos/" + repo + "/git/commits", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: "Ask.Me AI: implement requested changes", tree: tree.sha, parents: [parentSha] })
    });
    await gh(token, "/repos/" + repo + "/git/refs/heads/" + branch.split("/").map(encodeURIComponent).join("/"), {
      method: "PATCH", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sha: commit.sha, force: false })
    });
    return commit.sha;
  }
  async function monitorPullRequestChecks(token, repo, branch, pullRequestNumber, commitSha) {
    // A branch can have several workflows. Never report success from the first
    // completed run: wait for every PR-triggered run attached to this exact commit.
    const deadline = Date.now() + 600000;
    let latestRuns = [];
    let lastError = null;
    while (Date.now() < deadline) {
      try {
        const query = "/repos/" + repo + "/actions/runs?head_sha=" +
          encodeURIComponent(commitSha) + "&per_page=100";
        const runs = await gh(token, query);
        latestRuns = (runs.workflow_runs || []).filter(run =>
          run.head_sha === commitSha &&
          (run.pull_requests || []).some(pr => Number(pr.number) === Number(pullRequestNumber))
        );
        if (latestRuns.length && latestRuns.every(run => run.status === "completed")) break;
      } catch (error) {
        lastError = error;
      }
      await new Promise(resolve => setTimeout(resolve, 15000));
    }
    if (!latestRuns.length) {
      return {
        state: "not-detected",
        message: lastError
          ? "Could not retrieve PR checks: " + lastError.message
          : "No PR-triggered Actions runs were found for this exact commit. No passing result is claimed."
      };
    }
    const pending = latestRuns.filter(run => run.status !== "completed");
    if (pending.length) {
      return {
        state: "in-progress",
        url: pending[0].html_url,
        run_ids: latestRuns.map(run => run.id),
        message: "The verification deadline elapsed while one or more workflows were still running. No passing result is claimed."
      };
    }

    const runDetails = await Promise.all(latestRuns.map(async run => {
      const [jobData, artifactData] = await Promise.all([
        gh(token, "/repos/" + repo + "/actions/runs/" + run.id + "/jobs?per_page=100").catch(() => ({jobs:[]})),
        gh(token, "/repos/" + repo + "/actions/runs/" + run.id + "/artifacts?per_page=100").catch(() => ({artifacts:[]}))
      ]);
      const failed = (jobData.jobs || []).filter(job => job.conclusion === "failure");
      const logReports = await Promise.all(failed.slice(0, 3).map(async job => {
        try {
          const response = await fetch("https://api.github.com/repos/" + repo + "/actions/jobs/" + job.id + "/logs", {
            headers: ghHeaders(token), redirect: "follow"
          });
          const raw = await response.text();
          return { job: job.name, excerpt: raw.slice(-5000) };
        } catch (error) {
          return { job: job.name, excerpt: "Could not retrieve logs: " + error.message };
        }
      }));
      return {
        id: run.id,
        name: run.name,
        conclusion: run.conclusion || "unknown",
        url: run.html_url,
        jobs: (jobData.jobs || []).map(job => ({name:job.name,status:job.status,conclusion:job.conclusion})),
        failed_jobs: failed.map(job => job.name),
        failure_log_excerpts: logReports,
        artifacts: (artifactData.artifacts || []).filter(item => !item.expired)
          .map(item => ({name:item.name,size_in_bytes:item.size_in_bytes,download_url:item.archive_download_url}))
      };
    }));
    const failures = runDetails.filter(run => run.conclusion !== "success");
    return {
      state: failures.length ? "failure" : "success",
      url: runDetails[0]?.url,
      run_ids: runDetails.map(run => run.id),
      workflows: runDetails,
      failed_jobs: runDetails.flatMap(run => run.failed_jobs),
      artifacts: runDetails.flatMap(run => run.artifacts),
      message: failures.length
        ? "One or more PR workflows did not succeed. Review the per-workflow jobs and logs below."
        : "Every detected PR-triggered workflow for this exact commit completed successfully."
    };
  }
  const workflowSelect = document.getElementById("github-coding-workflow");
  const buildActionButton = document.getElementById("github-coding-build");
  const checkActionButton = document.getElementById("github-coding-check");
  const repairActionButton = document.getElementById("github-coding-repair");
  const deployActionButton = document.getElementById("github-coding-deploy");
  let lastPR = null, lastBranch = "", lastCommitSha = "", lastTask = "", lastChecks = null;
  try {
    const saved = JSON.parse(sessionStorage.getItem("askme_last_engineering_pr") || "null");
    if (saved && Number.isInteger(saved.number) && typeof saved.repo === "string") {
      lastPR = saved;
      lastBranch = saved.branch || "";
      lastCommitSha = saved.sha || "";
      if (repoInput && !repoInput.value) repoInput.value = saved.repo;
      lastTask = saved.task || "";
      checkActionButton.disabled = false;
      buildActionButton.disabled = false;
    }
  } catch (_) {}

  async function loadBuildWorkflows(token, repo, base) {
    if (!workflowSelect) return;
    workflowSelect.replaceChildren(new Option("Loading build workflows…", ""));
    try {
      const data = await gh(token, "/repos/" + repo + "/actions/workflows?per_page=100");
      const candidates = (data.workflows || []).filter(w => w.state === "active" &&
        /build|test|ci|validate|apk|android/i.test((w.name || "") + " " + (w.path || "")) &&
        !/deploy|release|publish/i.test((w.name || "") + " " + (w.path || "")));
      const supported = [];
      for (const workflow of candidates.slice(0, 25)) {
        try {
          const file = await getFile(token, repo, workflow.path, base);
          if (file && /^\s*workflow_dispatch\s*:/m.test(file.content)) supported.push(workflow);
        } catch (_) {}
      }
      workflowSelect.replaceChildren(new Option(
        supported.length ? "Choose build/test workflow" : "No manual build workflow found",
        ""
      ));
      for (const workflow of supported) {
        const option = new Option(workflow.name + " (" + workflow.path.split("/").pop() + ")", String(workflow.id));
        workflowSelect.appendChild(option);
      }
      if (buildActionButton) buildActionButton.disabled = !lastPR || !supported.length;
    } catch (error) {
      workflowSelect.replaceChildren(new Option("Could not load workflows — check Actions permission", ""));
      if (buildActionButton) buildActionButton.disabled = true;
    }
  }

  async function dispatchBuildWorkflow() {
    const repo = repoInput.value.trim(), token = tokenInput.value.trim();
    if (!lastPR || !lastBranch) throw new Error("Create a pull request first.");
    const workflowId = workflowSelect?.value;
    if (!workflowId) throw new Error("Select a build/test workflow first.");
    buildActionButton.disabled = true;
    status.textContent = "Dispatching the selected build workflow on " + lastBranch + "…";
    try {
      await gh(token, "/repos/" + repo + "/actions/workflows/" + encodeURIComponent(workflowId) + "/dispatches", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ref: lastBranch })
      });
      let run = null;
      for (let attempt = 0; attempt < 8 && !run; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 2000));
        const data = await gh(token, "/repos/" + repo + "/actions/runs?branch=" + encodeURIComponent(lastBranch) + "&event=workflow_dispatch&per_page=20");
        run = (data.workflow_runs || []).find(item =>
          Number(item.workflow_id) === Number(workflowId) && item.head_branch === lastBranch
        ) || null;
      }
      result.textContent = "Build workflow dispatched for branch " + lastBranch +
        (run ? "\nRun: " + run.html_url + "\nStatus: " + run.status : "\nGitHub accepted the dispatch. Open the repository Actions tab to follow the run.");
      status.textContent = "Build started. No build success is claimed until the workflow completes successfully.";
    } finally {
      buildActionButton.disabled = !lastPR || !workflowSelect?.value;
    }
  }

  async function refreshLatestPullRequestChecks() {
    const repo = repoInput.value.trim(), token = tokenInput.value.trim();
    if (!lastPR?.number) throw new Error("No pull request is recorded in this page session.");
    checkActionButton.disabled = true;
    status.textContent = "Reading the latest pull request commit and checking all GitHub Actions runs…";
    try {
      const pr = await gh(token, "/repos/" + repo + "/pulls/" + lastPR.number);
      await loadBuildWorkflows(token, repo, pr.base.ref);
      lastBranch = pr.head.ref;
      lastCommitSha = pr.head.sha;
      const checks = await monitorPullRequestChecks(token, repo, lastBranch, pr.number, lastCommitSha);
      lastChecks = checks;
      lastPR = { repo, number: pr.number, html_url: pr.html_url, branch: lastBranch, sha: lastCommitSha, merged: !!pr.merged_at, task: lastTask };
      sessionStorage.setItem("askme_last_engineering_pr", JSON.stringify(lastPR));
      repairActionButton.disabled = checks.state !== "failure";
      result.textContent = "Pull request: " + pr.html_url + "\nBranch: " + lastBranch + "\nCommit: " + lastCommitSha +
        "\n\nVerification: " + checks.state + " — " + checks.message +
        (checks.url ? "\nRun: " + checks.url : "") +
        (checks.failed_jobs?.length ? "\nFailed jobs: " + checks.failed_jobs.join(", ") : "") +
        (checks.workflows || []).flatMap(w => w.failure_log_excerpts || []).map(log =>
          "\n\n--- Failure log: " + log.job + " ---\n" + log.excerpt
        ).join("") +
        ((checks.artifacts || []).length ? "\n\nArtifacts: " + checks.artifacts.map(a => a.name).join(", ") : "");
      status.textContent = checks.state === "success" ? "All detected checks passed." :
        checks.state === "failure" ? "Checks failed. Review the logs or run Repair Failed Checks." :
        "Checks are pending or unavailable; no passing result is claimed.";
      return checks;
    } finally {
      checkActionButton.disabled = !lastPR;
    }
  }

  async function repairFailedPullRequest() {
    const repo = repoInput.value.trim(), token = tokenInput.value.trim();
    if (!lastPR?.number || !lastChecks || lastChecks.state !== "failure") throw new Error("Run Check PR & Debug first and wait for failed check logs.");
    const pr = await gh(token, "/repos/" + repo + "/pulls/" + lastPR.number);
    if (pr.head.repo?.full_name !== repo) throw new Error("This repair flow can push only to a pull request branch in the selected repository, not a fork.");
    const logs = (lastChecks.workflows || []).flatMap(w => w.failure_log_excerpts || [])
      .map(item => "FAILED JOB: " + item.job + "\n" + item.excerpt).join("\n\n").slice(0, 12000);
    if (!logs.trim()) throw new Error("GitHub did not provide failure logs. No repair commit was created.");
    const repairTask = "Debug and repair the failing GitHub Actions checks for this pull request. Preserve the requested feature and all working behavior. Use the actual failure logs below to identify root causes, make the smallest complete fix, and add or update tests where appropriate. Do not weaken tests or workflows to hide failures.\n\nOriginal task: " + lastTask + "\n\nFailure logs:\n" + logs;
    const treeData = await gh(token, "/repos/" + repo + "/git/trees/" + pr.head.sha + "?recursive=1");
    if (treeData.truncated) throw new Error("The PR branch tree is too large to inspect safely.");
    const candidates = relevantFiles(treeData.tree || [], repairTask);
    const loaded = [];
    let chars = 0;
    for (const file of candidates) {
      try {
        const source = await getFile(token, repo, file.path, pr.head.ref);
        if (!source || source.content.length > 14000 || chars + source.content.length > 36000) continue;
        loaded.push(source); chars += source.content.length;
        if (loaded.length >= 20) break;
      } catch (_) {}
    }
    if (!loaded.length) throw new Error("Could not read source files from the failed PR branch.");
    status.textContent = "Using failure logs to prepare a targeted repair…";
    const proposal = await askForPatch(repairTask, loaded, candidates.map(x => x.path));
    const validation = window.AskMeCodingGuard.validateProposal(proposal, loaded.map(file => file.path), {
      allowWorkflowChanges: /\b(workflow|github actions|ci\/cd|deployment workflow|deploy workflow)\b/i.test(repairTask)
    });
    if (!validation.ok) throw new Error(validation.message);
    status.textContent = "Committing the repair to the existing PR branch…";
    const commitSha = await commitFilesAtomically(token, repo, pr.head.ref, pr.head.sha, validation.files);
    lastBranch = pr.head.ref;
    lastCommitSha = commitSha;
    lastTask = repairTask;
    lastPR = { repo, number: pr.number, html_url: pr.html_url, branch: lastBranch, sha: lastCommitSha, merged: false, task: lastTask };
    sessionStorage.setItem("askme_last_engineering_pr", JSON.stringify(lastPR));
    status.textContent = "Repair committed. Monitoring the new checks…";
    lastChecks = await monitorPullRequestChecks(token, repo, lastBranch, pr.number, commitSha);
    repairActionButton.disabled = lastChecks.state !== "failure";
    result.textContent = "Repair commit: " + commitSha + "\nPull request: " + pr.html_url +
      "\nFiles: " + validation.files.map(f => f.path + (f.operation === "delete" ? " (deleted)" : "")).join(", ") +
      "\nVerification: " + lastChecks.state + " — " + lastChecks.message +
      (lastChecks.url ? "\nRun: " + lastChecks.url : "");
    status.textContent = lastChecks.state === "success" ? "Repair checks passed." : "Repair committed; verification still needs attention.";
  }

  async function deployVerifiedMain() {
    const repo = repoInput.value.trim(), token = tokenInput.value.trim();
    if (!lastPR?.number) throw new Error("Create a pull request in this session, then merge it after review before deploying.");
    const pr = await gh(token, "/repos/" + repo + "/pulls/" + lastPR.number);
    if (!pr.merged_at) throw new Error("Deployment is blocked: the recorded pull request has not been merged.");
    const repository = await gh(token, "/repos/" + repo);
    const base = repository.default_branch || "main";
    if (pr.base.ref !== base) throw new Error("Deployment is limited to the repository's default branch.");
    const ref = await gh(token, "/repos/" + repo + "/git/ref/heads/" + encodeURIComponent(base));
    const headSha = ref.object.sha;
    const checkData = await gh(token, "/repos/" + repo + "/commits/" + headSha + "/check-runs?per_page=100");
    const checkRuns = (checkData.check_runs || []).filter(check => !/deploy/i.test(check.name || ""));
    if (!checkRuns.length || checkRuns.some(check => check.status !== "completed" || check.conclusion !== "success")) {
      throw new Error("Deployment is blocked: the latest default-branch commit does not have a complete set of successful check runs.");
    }
    const workflowData = await gh(token, "/repos/" + repo + "/actions/workflows?per_page=100");
    const deployment = (workflowData.workflows || []).find(w => w.state === "active" && /deploy/i.test((w.name || "") + " " + (w.path || "")));
    if (!deployment) throw new Error("No active deployment workflow was found.");
    const workflowFile = await getFile(token, repo, deployment.path, base);
    if (!workflowFile || !/^\s*workflow_dispatch\s*:/m.test(workflowFile.content)) {
      throw new Error("The deployment workflow does not support a manual workflow_dispatch event.");
    }
    if (!window.confirm("Deploy the VERIFIED default branch " + base + " at " + headSha.slice(0, 12) + " to production? This does not merge any pull request.")) return;
    deployActionButton.disabled = true;
    status.textContent = "Dispatching the production deployment from verified " + base + "…";
    try {
      await gh(token, "/repos/" + repo + "/actions/workflows/" + encodeURIComponent(deployment.id) + "/dispatches", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ref: base })
      });
      result.textContent = "Production deployment workflow dispatched.\nWorkflow: " + deployment.name +
        "\nBranch: " + base + "\nCommit: " + headSha +
        "\nOpen the repository Actions tab to confirm the final deployment result.";
      status.textContent = "Deployment started; production success is not claimed until the workflow completes.";
    } finally {
      deployActionButton.disabled = false;
    }
  }

  if (buildActionButton) buildActionButton.addEventListener("click", async () => {
    try { await dispatchBuildWorkflow(); }
    catch (error) { status.textContent = "Build dispatch failed."; result.textContent = error.message; }
  });
  if (checkActionButton) checkActionButton.addEventListener("click", async () => {
    try { await refreshLatestPullRequestChecks(); }
    catch (error) { status.textContent = "PR check failed."; result.textContent = error.message; }
  });
  if (repairActionButton) repairActionButton.addEventListener("click", async () => {
    repairActionButton.disabled = true;
    try { await repairFailedPullRequest(); }
    catch (error) { status.textContent = "Repair failed."; result.textContent = error.message; }
    finally { repairActionButton.disabled = !lastChecks || lastChecks.state !== "failure"; }
  });
  if (deployActionButton) deployActionButton.addEventListener("click", async () => {
    try { await deployVerifiedMain(); }
    catch (error) { status.textContent = "Deployment blocked or failed."; result.textContent = error.message; }
  });

  clearButton.addEventListener("click", () => { tokenInput.value = ""; status.textContent = "Token cleared from the input. It was not saved by Ask.Me AI."; });
  runButton.addEventListener("click", async () => {
    const repo = repoInput.value.trim();
    const token = tokenInput.value.trim();
    const task = taskInput.value.trim();
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) { status.textContent = "Enter the repository as owner/name."; return; }
    if (!token) { status.textContent = "Paste a fine-grained GitHub token for this repository."; return; }
    if (!task) { status.textContent = "Describe the code change you want."; return; }
    runButton.disabled = true;
    result.textContent = "";
    let createdBranch = "";
    let pullRequestOpened = false;
    try {
      status.textContent = "Checking GitHub access and inspecting the repository...";
      const repository = await gh(token, "/repos/" + repo);
      if (!repository.permissions?.push) throw new Error("The GitHub token has no write access to this repository.");
      const base = repository.default_branch || "main";
      await loadBuildWorkflows(token, repo, base);
      const ref = await gh(token, "/repos/" + repo + "/git/ref/heads/" + encodeURIComponent(base));
      const treeData = await gh(token, "/repos/" + repo + "/git/trees/" + ref.object.sha + "?recursive=1");
      if (treeData.truncated) throw new Error("Repository is too large for a complete tree listing. Narrow the task or use a smaller repository.");
      const candidates = relevantFiles(treeData.tree || [], task);
      if (!candidates.length) throw new Error("No suitable source files were found in this repository.");
      const loaded = [];
      let chars = 0;
      for (const file of candidates) {
        try {
          const source = await getFile(token, repo, file.path, base);
          if (!source || source.content.length > 14000 || chars + source.content.length > 36000) continue;
          loaded.push(source); chars += source.content.length;
          if (loaded.length >= 20) break;
        } catch (_) {}
      }
      if (!loaded.length) throw new Error("Could not read usable source files. Check the token's Contents: read permission.");
      status.textContent = "Preparing a code change from " + loaded.length + " source file(s)...";
      const proposal = await askForPatch(task, loaded, candidates.map(x => x.path));
      lastTask = task;
      const validation = window.AskMeCodingGuard.validateProposal(proposal, loaded.map(file => file.path), {
        allowWorkflowChanges: /\b(workflow|github actions|ci\/cd|deployment workflow|deploy workflow)\b/i.test(task)
      });
      if (!validation.ok) {
        if (validation.noChanges) {
          result.textContent = validation.message;
          status.textContent = "No repository files changed.";
          return;
        }
        throw new Error(validation.message);
      }
      const files = validation.files;
      status.textContent = "Creating a separate branch and committing " + files.length + " file(s)...";
      const branch = "freeapp-ai/" + new Date().toISOString().replace(/[^0-9TZ]/g, "-").replace(/-+$/,"").toLowerCase() + "-" + window.AskMeCodingGuard.randomSuffix();
      await createBranch(token, repo, branch, ref.object.sha);
      createdBranch = branch;
      const commitSha = await commitFilesAtomically(token, repo, branch, ref.object.sha, files);
      lastBranch = branch;
      lastCommitSha = commitSha;
      status.textContent = "Opening a review pull request...";
      const body = [
        "## Ask.Me AI coding proposal",
        "",
        "**Request:** " + task,
        "",
        "**AI summary:** " + (proposal.summary || "Code changes proposed."),
        "",
        "### Suggested tests",
        (proposal.tests || []).map(x => "- " + x).join("\n") || "- Run the repository's existing test/build workflow.",
        "",
        "### Notes",
        (proposal.notes || []).map(x => "- " + x).join("\n") || "- Review the code and run the project's tests before merging.",
        "",
        "Generated by Ask.Me AI. This pull request was not auto-merged."
      ].join("\n").slice(0, 6000);
      const pr = await gh(token, "/repos/" + repo + "/pulls", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: "Ask.Me AI: " + (proposal.summary || task).slice(0, 180), head: branch, base, body })
      });
      pullRequestOpened = true;
      lastPR = { repo, number: pr.number, html_url: pr.html_url, branch, sha: commitSha, merged: false, task: lastTask };
      sessionStorage.setItem("askme_last_engineering_pr", JSON.stringify(lastPR));
      checkActionButton.disabled = false;
      buildActionButton.disabled = !workflowSelect?.value;
      status.textContent = "Pull request opened. Monitoring automated checks...";
      const checks = await monitorPullRequestChecks(token, repo, branch, pr.number, commitSha).catch(error => ({state:"unknown",message:error.message}));
      lastChecks = checks;
      repairActionButton.disabled = checks.state !== "failure";
      result.textContent = "Pull request created: " + pr.html_url + "\n\nBranch: " + branch + "\nAtomic commit: " + commitSha + "\nFiles changed: " + files.map(f => f.path).join(", ") +
        "\n\nSummary: " + (proposal.summary || "Code changes proposed.") +
        "\n\nSuggested tests: " + ((proposal.tests || []).join("; ") || "Repository Actions checks.") +
        "\n\nAutomated verification: " + checks.state + " — " + checks.message + (checks.url ? "\nRun: " + checks.url : "") +
        (checks.failed_jobs?.length ? "\nFailed jobs: " + checks.failed_jobs.join(", ") : "") +
        "\n\nThe agent creates reviewable changes and monitors CI. It does not merge or deploy without explicit authorization.";
      status.textContent = checks.state === "success" ? "Code committed; automated checks passed." : "Code committed; verification needs attention.";
    } catch (error) {
      if (createdBranch && !pullRequestOpened) {
        const refPath = "/repos/" + repo + "/git/refs/heads/" + createdBranch.split("/").map(encodeURIComponent).join("/");
        await gh(token, refPath, { method: "DELETE" }).catch(() => {});
      }
      status.textContent = "GitHub coding task failed.";
      result.textContent = String(error && error.message || error);
    } finally {
      runButton.disabled = false;
    }
  });
})();
