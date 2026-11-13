#!/usr/bin/env node
"use strict";

// Ask.Me App Builder 50-case live benchmark.
// Run: node tests/app-builder-benchmark.cjs --live
// No API token is required: this tests the public FreeApp-AI generation endpoint.

const fs = require("node:fs");
const path = require("node:path");

const ENDPOINT = process.env.ASKME_GENERATE_ENDPOINT || "https://freeapp-ai.lovesongmelodies.workers.dev/api/generate";
const OUT = process.env.BENCHMARK_OUT || "artifacts/askme-app-builder-benchmark.json";

const scenarios = [
  ["web","offline expense tracker with add/edit/delete transactions, category totals and local persistence","index.html styles.css app.js","localStorage transaction"],
  ["web","responsive school attendance register with student CRUD, date filtering and CSV export","index.html styles.css app.js","student attendance csv"],
  ["web","accessible task manager with priorities, due dates, filters and saved tasks","index.html styles.css app.js","task priority localStorage"],
  ["web","recipe organiser with search, tags, favourites and printable recipe details","index.html styles.css app.js","recipe search favourites"],
  ["web","personal budget dashboard with income, expenses, monthly summary and charts","index.html styles.css app.js","income expense monthly"],
  ["web","inventory manager with stock in/out, low-stock warnings and CSV export","index.html styles.css app.js","stock inventory export"],
  ["web","appointment scheduler with calendar list, validation and browser persistence","index.html styles.css app.js","appointment date validation"],
  ["web","quiz application with shuffled questions, score, restart and answer feedback","index.html styles.css app.js","quiz score restart"],
  ["web","small library catalogue with book search, borrow/return status and filters","index.html styles.css app.js","book search borrow"],
  ["web","customer contact manager with create, edit, delete and search","index.html styles.css app.js","contact edit delete"],
  ["web","responsive habit tracker with daily completion streaks and saved progress","index.html styles.css app.js","habit streak progress"],
  ["web","simple point-of-sale screen with cart, quantities, totals and receipt view","index.html styles.css app.js","cart total receipt"],
  ["web","water intake tracker with daily goal, progress and history","index.html styles.css app.js","water goal history"],
  ["web","event registration page with form validation, attendee list and export","index.html styles.css app.js","registration validation attendee"],
  ["web"," kanban board with draggable tasks, columns and persistent state","index.html styles.css app.js","kanban task columns"],
  ["web","flashcard study app with flip, shuffle, progress and custom cards","index.html styles.css app.js","flashcard shuffle progress"],
  ["web","small invoicing app with line items, tax, totals and printable invoice","index.html styles.css app.js","invoice tax print"],
  ["web","job application tracker with status filters, notes and date tracking","index.html styles.css app.js","job status filter"],
  ["web","offline notes app with create, edit, delete and search","index.html styles.css app.js","notes search localStorage"],
  ["web","responsive landing page for a local repair business with contact form validation","index.html styles.css app.js","responsive contact form"],
  ["android","Kotlin offline notes app with Room database, CRUD screens and tests","settings.gradle.kts build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin Room Activity"],
  ["android","Kotlin inventory app with stock movements, Room persistence and low-stock alerts","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin Room inventory"],
  ["android","Kotlin expense tracker with categories, monthly totals and local database","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin Room expense"],
  ["android","Kotlin attendance register with student records, date selection and export","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin attendance"],
  ["android","Kotlin habit tracker with daily check-ins, streaks and persistent storage","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin habit streak"],
  ["android","Kotlin quiz app with multiple choice questions, scoring and restart","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin quiz score"],
  ["android","Kotlin library app with book catalogue, search and borrow status","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin book search"],
  ["android","Kotlin appointment booking app with input validation and Room storage","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin appointment Room"],
  ["android","Kotlin contact manager with add, edit, delete and search","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin contact CRUD"],
  ["android","Kotlin point-of-sale app with cart, quantity controls and receipt summary","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin cart receipt"],
  ["android","Kotlin school timetable app with weekly schedule and reminders","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin timetable"],
  ["android","Kotlin flashcard app with study decks, shuffle and progress","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin flashcard"],
  ["android","Kotlin job tracker with application status, notes and filters","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin job status"],
  ["android","Kotlin event registration app with validated form and attendee records","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin event validation"],
  ["android","Kotlin calculator with input validation, history and unit tests","settings.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml","Kotlin calculator test"],
  ["fullstack","full-stack inventory API and web dashboard with CRUD, validation and tests","package.json server client schema test","API inventory CRUD"],
  ["fullstack","full-stack appointment booking system with availability checks and persistence","package.json server client schema test","API appointment availability"],
  ["fullstack","full-stack school attendance platform with student records, reports and tests","package.json server client schema test","API student attendance"],
  ["fullstack","full-stack expense manager with authenticated API design, categories and monthly reports","package.json server client schema test","API expense category"],
  ["fullstack","full-stack library system with catalogue, borrowing workflow and database schema","package.json server client schema test","API book borrow"],
  ["fullstack","full-stack event registration with attendee API, validation and CSV export","package.json server client schema test","API event attendee"],
  ["fullstack","full-stack task board with users, task status transitions and tests","package.json server client schema test","API task status"],
  ["fullstack","full-stack customer support ticket system with priorities and status history","package.json server client schema test","API ticket priority"],
  ["fullstack","full-stack invoicing app with line items, tax calculations and PDF-ready output","package.json server client schema test","API invoice tax"],
  ["fullstack","full-stack recipe sharing platform with search, favourites and moderation fields","package.json server client schema test","API recipe search"],
  ["fullstack","full-stack learning platform with courses, lessons, quiz results and progress","package.json server client schema test","API course progress"],
  ["fullstack","full-stack job application tracker with status workflow and notes","package.json server client schema test","API job status"],
  ["fullstack","full-stack point-of-sale backend with products, cart, stock and receipt records","package.json server client schema test","API cart stock"],
  ["fullstack","full-stack clinic appointment scheduler with patient-safe demo data and validation","package.json server client schema test","API appointment validation"],
  ["fullstack","full-stack community marketplace with listings, search, favourites and moderation","package.json server client schema test","API listing search"]
];

function promptFor([platform, requirement, expected, signals]) {
  const platformRules = platform === "android"
    ? "Generate a complete Android Studio Kotlin/Gradle project, including settings.gradle.kts, root and app Gradle files, AndroidManifest.xml, source, resources, and meaningful unit tests."
    : platform === "fullstack"
      ? "Generate a complete runnable full-stack project with frontend, backend API, data schema/migrations, package scripts, environment example without secrets, and automated tests."
      : "Generate a complete browser-runnable web project with index.html, styles.css, app.js and no unnecessary external dependencies.";
  return [
    "You are Ask.Me's application-building engineer. Implement the application, do not merely explain it.",
    "Requirement: " + requirement,
    platformRules,
    "Return each complete file in a fenced code block whose opening line is language followed by its relative path, for example: ```html index.html.",
    "Include all referenced files, dependencies, validation, error/empty states, setup instructions, exact run/test/build commands and tests.",
    "Do not use TODO placeholders for core functionality. Do not invent build, test, install or deployment results.",
    "Finish with a requirement-to-file acceptance checklist."
  ].join("\n\n");
}

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
  if (structured) return structured.files.map(file => {
    const filePath = String(file.path || file.name || "").trim();
    const language = String(file.language || file.lang || filePath.split(".").pop() || "text");
    return "```" + language + " " + filePath + "\\n" + String(file.content || file.code || "") + "\\n```";
  }).join("\\n\\n") + "\\n\\n" + String(structured.summary || structured.message || "");
  return "";
}

function extractFiles(answer, platform) {
  const files = [];
  const text = String(answer || "").replace(/\r\n/g, "\n");
  const re = /```([a-zA-Z0-9_+.#-]+)?[ \t]*([^\n`]*)\n([\s\S]*?)```/g;
  const defaults = platform === "android"
    ? { kotlin:"app/src/main/java/MainActivity.kt", kt:"app/src/main/java/MainActivity.kt", xml:"app/src/main/AndroidManifest.xml", gradle:"app/build.gradle.kts", "gradle.kts":"app/build.gradle.kts", properties:"gradle.properties" }
    : platform === "fullstack"
      ? { javascript:"server/index.js", js:"server/index.js", typescript:"server/index.ts", ts:"server/index.ts", json:"package.json", html:"client/index.html", css:"client/styles.css", sql:"database/schema.sql", python:"server/main.py" }
      : { html:"index.html", css:"styles.css", javascript:"app.js", js:"app.js", json:"package.json" };
  let match;
  while ((match = re.exec(text))) {
    const language = String(match[1] || "text").toLowerCase();
    let filePath = String(match[2] || "").trim().replace(/^[:*-]+\s*/, "").replace(/^\*\*|\*\*$/g, "");
    if (!filePath || filePath.startsWith("#") || filePath.length > 180 || filePath.includes(" ")) {
      const previous = text.slice(0, match.index).trimEnd().split("\n").pop() || "";
      const heading = previous.match(/(?:^|\s)([\w./-]+\.[a-zA-Z0-9]+)\s*[:：]?\s*$/);
      filePath = heading ? heading[1] : "";
    }
    if (!filePath) filePath = defaults[language] || "";
    if (!filePath || filePath.startsWith("/") || filePath.split(/[\\/]/).includes("..")) continue;
    const body = match[3].replace(/^\s*\n/, "").trimEnd();
    if (body.length < 8) continue;
    files.push({ language, path:filePath, body });
  }
  return files.filter((file,index,all)=>all.findIndex(item=>item.path===file.path)===index);
}

function scoreCase(test, response, body, payload = {}) {
  const checks = {};
  const files = extractFiles(body, test[0]);
  const lower = body.toLowerCase();
  const paths = files.map(f => f.path.toLowerCase()).join(" ");
  checks.http_ok = response.ok;
  checks.workers_ai_provider = payload.modelProvider === "Cloudflare Workers AI";
  checks.workers_ai_binding = payload.aiBindingConfigured === true;
  checks.real_model_response = payload.modelIndependentFallback !== true && payload.responseMode === "language-model";
  checks.nonempty_response = body.trim().length >= 300;
  checks.multiple_complete_files = files.length >= (test[0] === "web" ? 2 : 3);
  checks.no_path_traversal = files.every(f => !f.path.startsWith("/") && !f.path.split(/[\\/]/).includes(".."));
  checks.no_core_todo = !/TODO_IMPLEMENT|YOUR_CODE_HERE|implement this later/i.test(body);
  checks.has_tests = /test|spec/i.test(paths) || /unit test|automated test|test command/i.test(lower);
  checks.has_run_or_build_instructions = /npm run|gradle|gradlew|open .*html|run the app|build command|setup/i.test(lower);
  if (test[0] === "web") {
    checks.web_entrypoint = /index\.html/.test(paths);
    checks.web_styles = /\.css/.test(paths) || /<style/i.test(lower);
    checks.web_logic = /\.js/.test(paths) || /<script/i.test(lower);
  } else if (test[0] === "android") {
    checks.android_manifest = /androidmanifest\.xml/.test(paths);
    checks.android_gradle = /\.gradle\.kts|build\.gradle/.test(paths);
    checks.android_kotlin = /\.kt/.test(paths);
  } else {
    checks.backend_present = /server|api|route|controller/.test(paths);
    checks.frontend_present = /client|public|frontend|index\.html/.test(paths);
    checks.schema_present = /schema|migration|model|database/.test(paths);
  }
  const signalWords = test[3].toLowerCase().split(/\s+/).filter(w => w.length > 2);
  checks.requirement_signal_coverage = signalWords.filter(w => lower.includes(w)).length >= Math.max(2, Math.ceil(signalWords.length * 0.6));
  const passed = Object.values(checks).filter(Boolean).length;
  return { checks, passed, total: Object.keys(checks).length, percent: Math.round(100 * passed / Object.keys(checks).length), fileCount: files.length };
}

async function main() {
  if (!process.argv.includes("--live")) {
    console.error("Refusing to report a benchmark without live endpoint calls. Run with --live.");
    process.exit(2);
  }
  const results = [];
  for (let i = 0; i < scenarios.length; i++) {
    const test = scenarios[i];
    const started = Date.now();
    let response = { ok: false, status: 0 };
    let body = "";
    let payload = {};
    let error = "";
    try {
      response = await fetch(ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ mode: "app-builder", prompt: promptFor(test), images: [], history: [], memorySummary: "" }),
        signal: AbortSignal.timeout(180000)
      });
      payload = await response.json();
      body = normaliseGeneration(payload);
      if (!response.ok) error = payload.error || payload.detail || "HTTP " + response.status;
      if (!body && response.ok) error = "Empty or unrecognised response payload";
    } catch (e) { error = e.message || String(e); }
    const score = scoreCase(test, response, body, payload);
    results.push({ id: i + 1, platform: test[0], requirement: test[1], status: response.status, durationMs: Date.now() - started, error, responsePreview: body.slice(0, 1200), provider: payload && (payload.modelProvider || payload.provider || payload.model) || "not-reported", aiBindingConfigured: payload && typeof payload.aiBindingConfigured === "boolean" ? payload.aiBindingConfigured : null, codeFenceCount: (body.match(/\`\`\`/g) || []).length, ...score });
    console.log("[" + (i + 1) + "/50] " + test[0] + " " + score.percent + "% " + (error ? "- " + error : ""));
    await new Promise(resolve => setTimeout(resolve, 1200));
  }
  const checks = results.reduce((n, r) => n + r.total, 0);
  const passed = results.reduce((n, r) => n + r.passed, 0);
  const byPlatform = {};
  for (const platform of ["web", "android", "fullstack"]) {
    const group = results.filter(r => r.platform === platform);
    byPlatform[platform] = { cases: group.length, percent: Math.round(100 * group.reduce((n, r) => n + r.passed, 0) / group.reduce((n, r) => n + r.total, 0)) };
  }
  const report = {
    benchmark: "Ask.Me App Builder 50-case live benchmark",
    generatedAt: new Date().toISOString(),
    endpoint: ENDPOINT,
    authentication: "No bearer/API token supplied by this benchmark",
    cases: results.length,
    overallPercent: Math.round(100 * passed / checks),
    passedChecks: passed,
    totalChecks: checks,
    byPlatform,
    results
  };
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(report, null, 2));
  console.log("\nOVERALL: " + report.overallPercent + "% (" + passed + "/" + checks + " checks)");
  console.log("Report: " + OUT);
  if (report.overallPercent <= 90 || Object.values(byPlatform).some(p => p.percent <= 90)) process.exitCode = 1;
}

main().catch(error => { console.error(error); process.exit(1); });
