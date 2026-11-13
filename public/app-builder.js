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

  function extractFiles(answer) {
    const files = [];
    const pattern = /```([a-zA-Z0-9_+-]+)[ \t]+([^\n`]+)\n([\s\S]*?)```/g;
    let match;
    while ((match = pattern.exec(answer))) {
      const path = match[2].trim().replace(/^\*\*|\*\*$/g, "");
      if (!path || path.length > 180 || path.startsWith("/") || path.split(/[\\/]/).includes("..")) continue;
      files.push({ language: match[1].toLowerCase(), path, content: match[3].replace(/\s+$/, "") });
    }
    return files.filter((file, index, all) => all.findIndex(item => item.path === file.path) === index).slice(0, 30);
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
      "2. Return complete, production-minded source files. For EVERY file, use exactly this format: a fenced code block whose opening line contains the language and relative path, e.g. ```html index.html or ```kotlin app/src/main/java/MainActivity.kt. Put the entire file inside that block.",
      "3. Do not omit imports, dependencies, manifests, configuration, styles, or referenced files. Keep paths consistent across files.",
      "4. Implement all requested core interactions, input validation, loading/empty/error states, persistence where requested, and accessible labels.",
      "5. Include meaningful automated tests and exact local build/run commands. For Android, include Gradle wrapper/build configuration and a complete project tree.",
      "6. Explain assumptions and limitations. Do not say code was compiled, tested, installed, or deployed unless the operation actually happened.",
      "7. End with a concise acceptance checklist mapping each user requirement to the file that implements it.",
      "Do not modify Ask.Me itself or claim repository changes. This is a separate generated project."
    ].join("\n\n");
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 180000);
    try {
      const response = await fetch(API_BASE + "/api/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt, images: [], history: [], memorySummary: "" }),
        signal: controller.signal
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || data.detail || "App generation failed with HTTP " + response.status + ".");
      const answer = String(data.result || data.answer || data.response || "");
      if (!answer.trim()) throw new Error("The app builder received an empty response. Please retry.");
      const files = extractFiles(answer);
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
