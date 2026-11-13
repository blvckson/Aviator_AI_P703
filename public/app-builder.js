"use strict";
(() => {
  const byId = id => document.getElementById(id);
  const platform = byId("app-builder-platform");
  const spec = byId("app-builder-spec");
  const run = byId("app-builder-run");
  const clear = byId("app-builder-clear");
  const status = byId("app-builder-status");
  const output = byId("app-builder-output");
  const filesBox = byId("app-builder-files");
  const fileCount = byId("app-builder-file-count");
  const notes = byId("app-builder-notes");
  const previewWrap = byId("app-builder-preview-wrap");
  const preview = byId("app-builder-preview");
  const API_BASE = "https://freeapp-ai.lovesongmelodies.workers.dev";
  if (!run || !spec) return;

  function normaliseGeneration(data) {
    const candidates = [
      data && data.result, data && data.answer, data && data.response,
      data && data.output, data && data.content, data && data.generated_text,
      data && data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content,
      data && data.data && (data.data.result || data.data.answer || data.data.response || data.data.content)
    ];
    const value = candidates.find(item => typeof item === "string" && item.trim());
    if (value) return value;
    const structured = [data && data.result, data && data.data, data].find(item => item && typeof item === "object" && Array.isArray(item.files));
    if (structured) {
      return structured.files.map(file => {
        const path = String(file.path || file.name || "").trim();
        const language = String(file.language || file.lang || path.split(".").pop() || "text");
        return "```" + language + " " + path + "\n" + String(file.content || file.code || "") + "\n```";
      }).join("\n\n") + "\n\n" + String(structured.summary || structured.message || "");
    }
    return "";
  }

  function extractFiles(answer, appType) {
    const files = [];
    const text = String(answer || "").replace(/\r\n/g, "\n");
    const pattern = /```([a-zA-Z0-9_+.#-]+)?[ \t]*([^\n`]*)\n([\s\S]*?)```/g;
    const defaults = appType === "android"
      ? { kotlin: "app/src/main/java/MainActivity.kt", kt: "app/src/main/java/MainActivity.kt", xml: "app/src/main/AndroidManifest.xml", gradle: "app/build.gradle.kts", "gradle.kts": "app/build.gradle.kts", properties: "gradle.properties", json: "app/src/main/assets/data.json" }
      : appType === "fullstack"
        ? { javascript: "server/index.js", js: "server/index.js", typescript: "server/index.ts", ts: "server/index.ts", json: "package.json", html: "client/index.html", css: "client/styles.css", sql: "database/schema.sql", python: "server/main.py" }
        : { html: "index.html", css: "styles.css", javascript: "app.js", js: "app.js", json: "package.json" };
    let match;
    while ((match = pattern.exec(text))) {
      const language = String(match[1] || "text").toLowerCase();
      let filePath = String(match[2] || "").trim().replace(/^[:*-]+\s*/, "").replace(/^\*\*|\*\*$/g, "");
      if (!filePath || filePath.startsWith("#") || filePath.length > 180 || filePath.includes(" ")) {
        const before = text.slice(0, match.index).trimEnd().split("\n").pop() || "";
        const heading = before.match(/(?:^|\s)([\w./-]+\.[a-zA-Z0-9]+)\s*[:：]?\s*$/);
        filePath = heading ? heading[1] : "";
      }
      if (!filePath) filePath = defaults[language] || "";
      if (!filePath && language === "text") continue;
      if (filePath.startsWith("/") || filePath.split(/[\\/]/).includes("..")) continue;
      const content = match[3].replace(/^\s*\n/, "").trimEnd();
      if (content.length < 8) continue;
      files.push({ language, path: filePath, content });
    }
    return files.filter((file, index, all) => all.findIndex(item => item.path === file.path) === index).slice(0, 60);
  }

  function buildPreview(files) {
    const htmlFile = files.find(file => /\.html?$/i.test(file.path));
    if (!htmlFile) return "";
    let document = htmlFile.content;
    const css = files.filter(file => /\.css$/i.test(file.path)).map(file => file.content).join("\n");
    const scripts = files.filter(file => /\.js$/i.test(file.path)).map(file => file.content).join("\n");
    if (css) {
      if (/<\/head>/i.test(document)) document = document.replace(/<\/head>/i, "<style>" + css.replace(/<\/style/gi, "<\\/style") + "</style></head>");
      else document = "<style>" + css + "</style>" + document;
    }
    if (scripts) {
      if (/<\/body>/i.test(document)) document = document.replace(/<\/body>/i, "<script>" + scripts.replace(/<\/script/gi, "<\\/script") + "</script></body>");
      else document += "<script>" + scripts + "</script>";
    }
    return document;
  }

  function downloadFile(file) {
    const blob = new Blob([file.content], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = file.path.split("/").pop() || "app-source.txt";
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function showFiles(files, answer) {
    filesBox.replaceChildren();
    fileCount.textContent = files.length + " file(s)";
    for (const file of files) {
      const row = document.createElement("div");
      row.className = "app-builder-file";
      const name = document.createElement("span");
      name.className = "app-builder-file-name";
      name.textContent = file.path;
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = "Download";
      button.addEventListener("click", () => downloadFile(file));
      row.append(name, button);
      filesBox.appendChild(row);
    }
    const previewDocument = buildPreview(files);
    previewWrap.hidden = !previewDocument;
    if (previewDocument) preview.srcdoc = previewDocument;
    notes.textContent = answer;
    output.hidden = false;
  }

  run.addEventListener("click", async () => {
    const description = String(spec.value || "").trim();
    if (description.length < 20) {
      status.textContent = "Describe the app in at least 20 characters, including its main features.";
      return;
    }
    if (description.length > 12000) {
      status.textContent = "Please keep the app description under 12,000 characters.";
      return;
    }
    run.disabled = true;
    clear.disabled = true;
    output.hidden = true;
    status.textContent = "Planning the project, designing its structure, and generating source files…";
    const type = platform.value;
    const platformRules = type === "android"
      ? "Target Android. Produce Kotlin source, AndroidManifest.xml, Gradle files, layouts/resources where needed, and tests. Include every required file with its correct relative path. Never claim a build passed unless it was actually built."
      : type === "fullstack"
        ? "Target a full-stack web app. Include frontend, backend API, data model/schema, configuration example without secrets, and tests. Include setup and run instructions. Never invent credentials or claim deployment."
        : "Target a browser-runnable web app. Generate index.html, styles.css, and app.js (or a single index.html if truly appropriate). Make it functional, responsive, accessible, and self-contained; use no external dependencies unless explicitly requested.";
    const prompt = [
      "You are Ask.Me's dedicated application-building engineer. Build the requested application, not merely describe how to build it.",
      "APP TYPE: " + type.toUpperCase(),
      "USER REQUIREMENTS:\n" + description,
      platformRules,
      "DELIVERY CONTRACT:",
      "1. Start with a short architecture and implementation plan.",
      "2. SOURCE-FIRST OUTPUT: put complete source files before explanations. Every file MUST be a fenced code block with language and path on the opening line (example: ```html index.html). Never replace source with a plan, pseudo-code, or a list of files.",
      "3. Include every file needed to run the project. Web: index.html, styles.css, app.js. Android: Gradle settings/build files, manifest, Kotlin source, resources and tests. Full-stack: package.json, frontend, backend routes, data schema and tests. Keep all paths consistent.",
      "4. Implement all requested core interactions, input validation, loading/empty/error states, persistence where requested, and accessible labels.",
      "5. Include meaningful automated tests and exact local build/run commands. For Android include Gradle wrapper/build configuration and a complete project tree. If output limits are reached, prioritise runnable source files over lengthy explanation.",
      "6. Explain assumptions and limitations. Do not say code was compiled, tested, installed, or deployed unless the operation actually happened.",
      "7. End with a concise acceptance checklist mapping each user requirement to the file that implements it.",
      "Engineering quality: return complete, coherent, runnable source code rather than a sketch. Validate inputs, handle failures, include meaningful tests and build/run steps. Never claim code was compiled or executed unless it actually was.",
      "Do not modify Ask.Me itself or claim repository changes. This is a separate generated project."
    ].join("\n\n");
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 180000);
    try {
      const response = await fetch(API_BASE + "/api/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mode: "app-builder", prompt, images: [], history: [], memorySummary: "" }),
        signal: controller.signal
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || data.detail || "App generation failed with HTTP " + response.status + ".");
      const answer = normaliseGeneration(data);
      if (!answer.trim()) throw new Error("The app builder received an empty or unsupported generation response. Please retry.");
      if (/deterministic evidence synthesis|generation services failed|no usable source text|independent research result/i.test(answer)) {
        throw new Error("Application generation is currently unavailable: the service returned a research fallback instead of generated source code. No project files were created. Please retry after generation service recovery.");
      }
      const files = extractFiles(answer, type);
      showFiles(files, answer);
      status.textContent = files.length
        ? "App generation finished. " + files.length + " source file(s) extracted; preview and downloads are ready. No build or device test has been claimed."
        : "The model returned build guidance but no correctly labelled source files. See the complete response below and retry with a more specific request.";
      if (!files.length) {
        notes.textContent = answer;
        output.hidden = false;
      }
    } catch (error) {
      status.textContent = error.name === "AbortError" ? "App generation timed out. Your requirements are still here; retry when ready." : (error.message || "App generation failed.");
    } finally {
      clearTimeout(timeout);
      run.disabled = false;
      clear.disabled = false;
    }
  });

  clear.addEventListener("click", () => {
    spec.value = "";
    filesBox.replaceChildren();
    notes.textContent = "";
    preview.srcdoc = "";
    output.hidden = true;
    status.textContent = "Ready. Describe the app you want built.";
  });
})();
