"use strict";

const byId = (id) => document.getElementById(id);
const promptBox = byId("prompt");
const imageInput = byId("image-input");
const fileInput = byId("file-input");
const docFileStatus = byId("doc-file-status");
const fileStatus = byId("file-status");
const preview = byId("preview");
const resultBox = byId("result");
const mediaBox = byId("media");
const statusBox = byId("status");
const sourcesBox = byId("sources");
const sourceList = byId("source-list");
const buildButton = byId("build-button");
const narratorButton = byId("narrator-button");
const narratorStopButton = byId("narrator-stop");
const narratorSpeed = byId("narrator-speed");
const narratorSpeedValue = byId("narrator-speed-value");
const narratorAutoRead = byId("narrator-auto-read");
const voiceCloneButton = byId("narrator-settings-button");
const voiceCloneModal = byId("narrator-modal");
const voiceCloneClose = byId("narrator-close");

const API_BASE = "https://freeapp-ai.lovesongmelodies.workers.dev";
const MAX_IMAGES = 4;
const MAX_DOC_FILES = 5;
const MAX_DOC_BYTES = 100000;
const MAX_DOC_CHARS = 12000;
const MAX_PROMPT_CHARS = 24000;
const REQUEST_TIMEOUT_MS = 120000;
const DEFAULT_NARRATOR_RATE = 0.90;
let selectedImages = [];
let selectedDocs = [];
let lastAnswer = "";
let speechQueue = [];
let speaking = false;

function readPreference(key, fallback) {
  try {
    const value = localStorage.getItem(key);
    return value === null ? fallback : JSON.parse(value);
  } catch (_) {
    return fallback;
  }
}
function savePreference(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (_) { /* storage may be disabled */ }
}
function getNarratorRate() {
  const value = Number(narratorSpeed && narratorSpeed.value);
  return Number.isFinite(value) ? Math.min(1.5, Math.max(0.3, value)) : DEFAULT_NARRATOR_RATE;
}
function updateNarratorRateLabel() {
  if (narratorSpeedValue) narratorSpeedValue.textContent = getNarratorRate().toFixed(2) + "×";
}
function stopNarrator() {
  speechQueue = [];
  speaking = false;
  if (window.AskMeNativeTTS && typeof window.AskMeNativeTTS.stop === "function") window.AskMeNativeTTS.stop();
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
  if (narratorStopButton) narratorStopButton.disabled = true;
  if (narratorButton) narratorButton.disabled = !lastAnswer;
}
function getPreferredVoice() {
  if (!("speechSynthesis" in window)) return null;
  const voices = window.speechSynthesis.getVoices() || [];
  return voices.find(v => /^en-UG$/i.test(v.lang)) ||
    voices.find(v => /^en-GB/i.test(v.lang)) ||
    voices.find(v => /^en-/i.test(v.lang)) || null;
}
function speakNextChunk() {
  if (!speechQueue.length) {
    speaking = false;
    if (narratorStopButton) narratorStopButton.disabled = true;
    if (narratorButton) narratorButton.disabled = !lastAnswer;
    return;
  }
  const text = speechQueue.shift();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.rate = getNarratorRate();
  utterance.pitch = 0.96;
  utterance.volume = 1;
  const voice = getPreferredVoice();
  if (voice) utterance.voice = voice;
  utterance.onend = speakNextChunk;
  utterance.onerror = () => {
    speaking = false;
    speechQueue = [];
    if (narratorStopButton) narratorStopButton.disabled = true;
    if (statusBox) statusBox.textContent = "Narrator playback stopped. You can try again.";
  };
  window.speechSynthesis.speak(utterance);
}
function speakText(text) {
  const plainText = String(text || "").replace(/\s+/g, " ").trim();
  if (!plainText) return false;
  stopNarrator();
  // Prefer sentence boundaries and short breath-sized groups for composed narration.
  const sentences = plainText.match(/[^.!?]+[.!?]+["')\\]]*|[^.!?]+$/g) || [plainText];
  const cleanChunks = [];
  let current = "";
  for (const sentence of sentences) {
    const part = sentence.trim();
    if (!part) continue;
    if (current && current.length + part.length + 1 > 420) {
      cleanChunks.push(current.trim());
      current = "";
    }
    current += (current ? " " : "") + part;
  }
  if (current.trim()) cleanChunks.push(current.trim());
  if (window.AskMeNativeTTS && typeof window.AskMeNativeTTS.speakChunk === "function" &&
      typeof window.AskMeNativeTTS.isReady === "function" && window.AskMeNativeTTS.isReady()) {
    const rate = getNarratorRate();
    const accepted = cleanChunks.every((chunk, index) => window.AskMeNativeTTS.speakChunk(chunk, rate, index > 0));
    if (accepted) {
      speaking = true;
      if (narratorStopButton) narratorStopButton.disabled = false;
      if (narratorButton) narratorButton.disabled = true;
      return true;
    }
    window.AskMeNativeTTS.stop();
  }
  if (!("speechSynthesis" in window) || typeof SpeechSynthesisUtterance === "undefined") {
    statusBox.textContent = "Speech playback is not supported by this Android WebView.";
    return false;
  }
  speechQueue = cleanChunks;
  speaking = true;
  if (narratorStopButton) narratorStopButton.disabled = false;
  if (narratorButton) narratorButton.disabled = true;
  speakNextChunk();
  return true;
}

if (narratorSpeed) {
  narratorSpeed.value = String(readPreference("askme_narrator_rate", DEFAULT_NARRATOR_RATE));
  narratorSpeed.addEventListener("input", () => {
    updateNarratorRateLabel();
    savePreference("askme_narrator_rate", getNarratorRate());
  });
}
if (narratorAutoRead) {
  narratorAutoRead.checked = !!readPreference("askme_narrator_auto_read", false);
  narratorAutoRead.addEventListener("change", () => savePreference("askme_narrator_auto_read", narratorAutoRead.checked));
}
updateNarratorRateLabel();
if (narratorButton) narratorButton.addEventListener("click", () => speakText(lastAnswer));
if (narratorStopButton) {
  narratorStopButton.disabled = true;
  narratorStopButton.addEventListener("click", stopNarrator);
}
if (voiceCloneButton && voiceCloneModal) voiceCloneButton.addEventListener("click", () => { voiceCloneModal.hidden = false; });
if (voiceCloneClose && voiceCloneModal) voiceCloneClose.addEventListener("click", () => { voiceCloneModal.hidden = true; });

if (imageInput) imageInput.addEventListener("change", () => {
  selectedImages = Array.from(imageInput.files || []).filter(file => file.type.startsWith("image/")).slice(0, MAX_IMAGES);
  if (preview) {
    for (const child of Array.from(preview.querySelectorAll("img[data-preview-url]"))) URL.revokeObjectURL(child.dataset.previewUrl);
    preview.replaceChildren();
  }
  if (fileStatus) fileStatus.textContent = selectedImages.length
    ? selectedImages.length + " image(s) selected (maximum " + MAX_IMAGES + ")"
    : "No supported image selected";
  for (const file of selectedImages) {
    const img = document.createElement("img");
    const objectUrl = URL.createObjectURL(file);
    img.src = objectUrl;
    img.dataset.previewUrl = objectUrl;
    img.alt = "Selected screenshot";
    img.className = "preview-image";
    preview.appendChild(img);
  }
});
if (fileInput) fileInput.addEventListener("change", () => {
  selectedDocs = Array.from(fileInput.files || [])
    .filter(file => /\\.(txt|md|csv|json|py|js|kt|java|html|css|xml|log)$/i.test(file.name))
    .filter(file => file.size <= MAX_DOC_BYTES)
    .slice(0, MAX_DOC_FILES);
  if (docFileStatus) {
    const rejected = Math.max(0, (fileInput.files || []).length - selectedDocs.length);
    docFileStatus.textContent = selectedDocs.length
      ? selectedDocs.length + " document(s) selected" + (rejected ? "; " + rejected + " skipped (type/size/limit)" : "")
      : "No supported documents selected";
  }
});
if (buildButton) buildButton.addEventListener("click", askAI);

function buildQualityPrompt(userPrompt) {
  const rules = [];
  if (/research|latest|current|today|source|citation|verify|fact.?check/i.test(userPrompt)) {
    rules.push("Research integrity: retrieve current sources when live research is available; include direct, relevant source URLs; distinguish verified facts from inference; never invent sources or citations. If live retrieval is unavailable, state that clearly and do not claim that research was completed.");
  }
  if (/build|create|develop|implement|app|software|program|code|function|script/i.test(userPrompt)) {
    rules.push("Engineering quality: deliver complete, coherent, runnable code rather than a sketch when code is requested; validate inputs; handle failures; include meaningful tests and build/run steps. For user interfaces, use semantic HTML and accessible labels for every control, and avoid external dependencies unless requested. Never claim that code was compiled or executed unless it actually was.");
  }
  if (/topological|directed graph|\bDAG\b|edge\[0\]|edge\[1\]/i.test(userPrompt)) {
    rules.push("Graph/code self-audit: before returning the implementation, validate BOTH endpoints of every edge (source and target) against the declared node set before indexing or mutating graph state. Include tests for an unknown source endpoint and an unknown target endpoint. Do not omit either check.");
  }
  if (/html|web app|form|input|button|accessible|task manager/i.test(userPrompt)) {
    rules.push("Accessibility self-audit: inspect every generated input, button and select control. Each must have an accessible name through a connected <label for> or a non-empty aria-label/aria-labelledby. Do not rely on placeholder text alone.");
  }
  if (/debug|fix|repair|broken|error|bug|crash/i.test(userPrompt)) {
    rules.push("Debugging quality: identify the root cause, show the corrected implementation, explain why it fixes the defect, and include a regression test for the reported failure.");
  }
  if (/reason|logic|calculate|solve|prove|analy[sz]e/i.test(userPrompt)) {
    rules.push("Reasoning quality: work through the key steps, verify the conclusion against the original conditions, and state uncertainty or ambiguity rather than guessing.");
  }
  if (/pull request|\bPR\b|push|pull|commit|deploy|deployment|github action|repository/i.test(userPrompt)) {
    rules.push("Repository-operation honesty: do not claim a repository was read, changed, committed, pushed, pulled, built, or deployed unless the corresponding authenticated operation was actually completed and its result verified. Otherwise state what access or operation is missing.");
  }
  return rules.length
    ? userPrompt + "\n\nAsk.Me quality requirements:\n- " + rules.join("\n- ")
    : userPrompt;
}

async function askAI() {
  const prompt = String(promptBox && promptBox.value || "").trim();
  if (!prompt && !selectedImages.length && !selectedDocs.length) {
    statusBox.textContent = "Enter a question or attach an image first.";
    return;
  }
  if (prompt.length > MAX_PROMPT_CHARS) {
    statusBox.textContent = "Please shorten the prompt to " + MAX_PROMPT_CHARS + " characters or fewer.";
    return;
  }
  buildButton.disabled = true;
  statusBox.textContent = "Analyzing your request and researching relevant sources...";
  resultBox.textContent = "";
  lastAnswer = "";
  if (narratorButton) narratorButton.disabled = true;
  if (mediaBox) mediaBox.replaceChildren();
  if (sourceList) sourceList.replaceChildren();
  if (sourcesBox) sourcesBox.hidden = true;

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  const timeoutId = controller ? setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS) : null;
  try {
    const images = [];
    for (const file of selectedImages) images.push(await toDataUrl(file));
    const documentParts = [];
    for (const file of selectedDocs) {
      const content = await toText(file);
      documentParts.push("--- ATTACHED FILE: " + file.name + " ---\n" + content);
    }
    const effectivePrompt = [prompt || (images.length ? "Please analyze the attached image(s) and describe the relevant details." : ""), ...documentParts].filter(Boolean).join("\n\n");
    if (effectivePrompt.length > MAX_PROMPT_CHARS) {
      throw new Error("The question and attached text exceed " + MAX_PROMPT_CHARS + " characters. Attach fewer or shorter files.");
    }
    const response = await fetch(API_BASE + "/api/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ prompt: buildQualityPrompt(effectivePrompt), images, history: [], memorySummary: "" }),
      ...(controller ? { signal: controller.signal } : {})
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || data.detail || ("AI service returned HTTP " + response.status + "."));
    const answer = typeof data.result === "string" ? data.result : (typeof data.answer === "string" ? data.answer : "");
    if (!answer.trim()) throw new Error("The AI service returned an empty answer. Please retry.");
    lastAnswer = answer;
    resultBox.textContent = answer;
    renderMedia(data.media);
    renderSources(data.sources);
    const researchIntent = /research|latest|current|today|source|citation|verify|fact.?check/i.test(prompt);
    const hasVerifiedSources = data.researched === true && Array.isArray(data.sources) &&
      data.sources.some(source => source && typeof source.url === "string" && /^https?:\/\//i.test(source.url));
    statusBox.textContent = hasVerifiedSources
      ? "Done — live research sources were returned."
      : (researchIntent ? "Answer returned, but live source verification was not confirmed." : "Done.");
    if (narratorAutoRead && narratorAutoRead.checked) speakText(answer);
  } catch (error) {
    const timedOut = error && (error.name === "AbortError" || /aborted/i.test(error.message || ""));
    statusBox.textContent = timedOut ? "The request timed out. Your text is safe; please retry." : "Request failed.";
    resultBox.textContent = timedOut ? "Ask.Me AI did not receive a response within two minutes." : (error.message || "Unexpected request error.");
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
    buildButton.disabled = false;
    if (narratorButton) narratorButton.disabled = !lastAnswer;
  }
}

function renderSources(sources) {
  if (!Array.isArray(sources) || !sourceList || !sourcesBox) return;
  for (const source of sources) {
    if (!source || typeof source.url !== "string" || !/^https?:\/\//i.test(source.url)) continue;
    const li = document.createElement("li");
    const a = document.createElement("a");
    a.href = source.url;
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    a.textContent = source.title || source.url;
    li.appendChild(a);
    sourceList.appendChild(li);
  }
  sourcesBox.hidden = sourceList.children.length === 0;
}
function renderMedia(media) {
  if (!Array.isArray(media) || !mediaBox) return;
  for (const item of media) {
    if (!item || typeof item.url !== "string" || !/^https?:\/\//i.test(item.url)) continue;
    const wrap = document.createElement("div");
    wrap.className = "media-card";
    if (item.type === "image") {
      const img = document.createElement("img");
      img.src = item.url;
      img.alt = item.title || "Relevant image";
      img.loading = "lazy";
      wrap.appendChild(img);
    } else if (item.type === "video") {
      const link = document.createElement("a");
      link.href = item.url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = "▶ " + (item.title || "Watch relevant video");
      wrap.appendChild(link);
    } else continue;
    if (item.title) {
      const caption = document.createElement("div");
      caption.textContent = item.title;
      wrap.appendChild(caption);
    }
    mediaBox.appendChild(wrap);
  }
}
function toText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || "").slice(0, MAX_DOC_CHARS));
    reader.onerror = () => reject(new Error("Could not read attached file: " + file.name));
    reader.readAsText(file);
  });
}
function toDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error("Could not read the selected image."));
    reader.readAsDataURL(file);
  });
}
