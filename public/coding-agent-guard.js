(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.FreeAppCodingGuard = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const MAX_FILES = 12, MAX_TOTAL_CHARS = 30000, MAX_FILE_CHARS = 20000;
  const ALLOWED_EXT = /\.(?:js|mjs|ts|tsx|jsx|html|css|kt|java|gradle|kts|xml|json|py|go|rs|swift|dart|yml|yaml|md|c|cc|cpp|h|hpp|sh|properties|toml)$/i;

  function safePath(path) {
    if (typeof path !== "string" || !path || path.length > 240) return false;
    if (path.startsWith("/") || path.includes("\\") || path.includes("\0")) return false;
    const parts = path.split("/");
    if (parts.some(part => !part || part === "." || part === "..")) return false;
    if (/(^|\/)(?:\.git|\.env[^/]*|secrets?[^/]*)(?:\/|$)/i.test(path)) return false;
    if (/\.(?:pem|key|p12|keystore|jks|apk|aab)$/i.test(path) || !ALLOWED_EXT.test(path)) return false;
    if (/(^|\/)(?:node_modules|build|dist|vendor|\.gradle|\.idea)(?:\/|$)/i.test(path)) return false;
    if (/(?:^|\/)package-lock\.json$|(?:^|\/)yarn\.lock$|(?:^|\/)pnpm-lock\.yaml$/i.test(path)) return false;
    return true;
  }

  function validateProposal(proposal, inspectedPaths) {
    const noChange = message => ({ ok: false, noChanges: true, message });
    const fail = message => ({ ok: false, noChanges: false, message });
    if (!proposal || typeof proposal !== "object" || Array.isArray(proposal)) return fail("The AI returned an invalid proposal object.");
    if (typeof proposal.summary !== "string" || proposal.summary.length > 1200) return fail("The AI proposal summary is missing or too long.");
    if (!Array.isArray(proposal.tests) || !proposal.tests.every(x => typeof x === "string" && x.length <= 500)) return fail("The AI returned an invalid test list.");
    if (!Array.isArray(proposal.notes) || !proposal.notes.every(x => typeof x === "string" && x.length <= 1000)) return fail("The AI returned invalid implementation notes.");
    if (!Array.isArray(proposal.files)) return fail("The AI response did not contain a valid files list.");
    if (!proposal.files.length) return noChange((proposal.summary || "No changes proposed.") + "\n\n" + proposal.notes.join("\n"));
    if (proposal.files.length > MAX_FILES) return fail("The AI proposed more than " + MAX_FILES + " files. No repository changes were made.");

    const inspected = new Set(Array.isArray(inspectedPaths) ? inspectedPaths : []);
    const directories = [...inspected].map(path => path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "").filter(Boolean);
    const seen = new Set();
    let total = 0;
    const files = [];
    for (const file of proposal.files) {
      if (!file || typeof file !== "object" || !safePath(file.path) || typeof file.content !== "string") return fail("The AI returned an invalid, unsupported, or disallowed file path. No changes were made.");
      if (seen.has(file.path)) return fail("The AI proposed the same file more than once.");
      seen.add(file.path);
      if (file.content.length > MAX_FILE_CHARS) return fail("A proposed file exceeds the per-file size limit.");
      total += file.content.length;
      if (total > MAX_TOTAL_CHARS) return fail("The proposed change exceeds the total size limit. No changes were made.");
      const existing = inspected.has(file.path);
      const newInInspectedDirectory = !existing && directories.some(directory => file.path.startsWith(directory + "/"));
      if (!existing && !newInInspectedDirectory) return fail("The AI proposed a file outside the inspected source files and directories: " + file.path);
      files.push({ path: file.path, content: file.content });
    }
    return { ok: true, noChanges: false, files, summary: proposal.summary, tests: proposal.tests, notes: proposal.notes };
  }

  function randomSuffix() {
    const bytes = new Uint8Array(4);
    if (typeof crypto !== "undefined" && crypto.getRandomValues) crypto.getRandomValues(bytes);
    else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    return Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  }
  return { safePath, validateProposal, randomSuffix };
});
