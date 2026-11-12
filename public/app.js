"use strict";

const byId = (id) => document.getElementById(id);
const promptBox = byId("prompt");
const imageInput = byId("image-input");
const fileInput = byId("file-input");
const videoInput = byId("video-input");
const videoFileStatus = byId("video-file-status");
const docFileStatus = byId("doc-file-status");
const fileStatus = byId("file-status");
const preview = byId("preview");
const resultBox = byId("result");
const mediaBox = byId("media");
const statusBox = byId("status");
const sourcesBox = byId("sources");
const sourceList = byId("source-list");
const buildButton = byId("build-button");
const chatMessages = byId("chat-messages");
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
const MAX_DOC_BYTES = 12 * 1024 * 1024;
const MAX_DOC_CHARS = 12000;
const MAX_PROMPT_CHARS = 24000;
const REQUEST_TIMEOUT_MS = 120000;
const DEFAULT_NARRATOR_RATE = 0.90;
let selectedImages = [];
let selectedDocs = [];
let selectedVideos = [];
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
function splitNarratorChunks(text, maxLength = 480) {
  const sentences = String(text || "").match(/[^.!?…]+(?:[.!?…]+["'”’)]*|$)/gu) || [String(text || "")];
  const chunks = [];
  let current = "";
  for (const rawSentence of sentences) {
    const sentence = rawSentence.trim();
    if (!sentence) continue;
    if (sentence.length > maxLength) {
      if (current) { chunks.push(current); current = ""; }
      const words = sentence.split(/\s+/);
      let part = "";
      for (const word of words) {
        if (part && (part + " " + word).length > maxLength) {
          chunks.push(part);
          part = word;
        } else {
          part = part ? part + " " + word : word;
        }
      }
      if (part) current = part;
      continue;
    }
    if (current && (current + " " + sentence).length > maxLength) {
      chunks.push(current);
      current = sentence;
    } else {
      current = current ? current + " " + sentence : sentence;
    }
  }
  if (current) chunks.push(current);
  return chunks;
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
  utterance.pitch = 0.98;
  utterance.volume = 1;
  const voice = getPreferredVoice();
  if (voice) utterance.voice = voice;
  utterance.onend = () => { setTimeout(speakNextChunk, 140); };
  utterance.onerror = () => {
    speaking = false;
    speechQueue = [];
    if (narratorStopButton) narratorStopButton.disabled = true;
    if (statusBox) statusBox.textContent = "Narrator playback stopped. You can try again.";
  };
  window.speechSynthesis.speak(utterance);
}
async function waitForNativeNarrator(timeoutMs = 8000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      if (window.AskMeNativeTTS && typeof window.AskMeNativeTTS.isReady === "function" && window.AskMeNativeTTS.isReady()) return true;
    } catch (_) { /* Native bridge may still be initializing. */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return false;
}
window.askMeNarrationFinished = function () {
  speaking = false;
  if (narratorStopButton) narratorStopButton.disabled = true;
  if (narratorButton) narratorButton.disabled = !lastAnswer;
};
function speakText(text) {
  const plainText = String(text || "").replace(/\s+/g, " ").trim();
  if (!plainText) return false;
  stopNarrator();
  const chunks = splitNarratorChunks(plainText, 480);
  const cleanChunks = chunks.map(chunk => chunk.trim()).filter(Boolean);
  if (window.AskMeNativeTTS && typeof window.AskMeNativeTTS.speakChunk === "function") {
    speaking = true;
    if (narratorStopButton) narratorStopButton.disabled = false;
    if (narratorButton) narratorButton.disabled = true;
    waitForNativeNarrator().then(ready => {
      if (!speaking) return;
      if (!ready) {
        speaking = false;
        if (narratorStopButton) narratorStopButton.disabled = true;
        if (narratorButton) narratorButton.disabled = !lastAnswer;
        if (statusBox) statusBox.textContent = "Android text-to-speech did not initialize. Check that a speech engine is installed.";
        return;
      }
      const rate = getNarratorRate();
      const accepted = cleanChunks.every((chunk, index) => window.AskMeNativeTTS.speakChunk(chunk, rate, index > 0, index === cleanChunks.length - 1));
      if (!accepted) {
        stopNarrator();
        if (statusBox) statusBox.textContent = "Android text-to-speech could not start. Please try again.";
      }
    });
    return true;
  }
  if (!("speechSynthesis" in window) || typeof SpeechSynthesisUtterance === "undefined") {
    if (statusBox) statusBox.textContent = "Speech playback is not supported by this browser.";
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
    .filter(file => /\.(txt|md|csv|json|py|js|kt|java|html|css|xml|log|pdf|docx)$/i.test(file.name))
    .filter(file => file.size <= MAX_DOC_BYTES)
    .slice(0, MAX_DOC_FILES);
  if (docFileStatus) {
    const rejected = Math.max(0, (fileInput.files || []).length - selectedDocs.length);
    docFileStatus.textContent = selectedDocs.length
      ? selectedDocs.length + " document(s) selected" + (rejected ? "; " + rejected + " skipped (type/size/limit)" : "")
      : "No supported documents selected";
  }
});
if (videoInput) videoInput.addEventListener("change", () => {
  selectedVideos = Array.from(videoInput.files || []).filter(file => file.type.startsWith("video/")).slice(0, 2);
  if (videoFileStatus) videoFileStatus.textContent = selectedVideos.length ? selectedVideos.length + " video(s) selected (maximum 2)" : "No video selected";
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
  if (/pull request|\bPR\b|push|pull|commit|deploy|deployment|github action|repository/i.test(userPrompt)) {
    rules.push("Repository-operation honesty: do not claim a repository was read, changed, committed, pushed, pulled, built, or deployed unless the corresponding authenticated operation was actually completed and its result verified. Otherwise state what access or operation is missing.");
  }
  return rules.length
    ? userPrompt + "\n\nAsk.Me quality requirements:\n- " + rules.join("\n- ")
    : userPrompt;
}

async function askAI() {
  const prompt = String(promptBox && promptBox.value || "").trim();
  if (!prompt && !selectedImages.length && !selectedDocs.length && !selectedVideos.length) {
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

  const userBubble = appendChatMessage("user", prompt || "Please analyze the attached media and files.");
  const assistantBubble = appendChatMessage("assistant", "Thinking…");
  const greeting = !selectedImages.length && !selectedDocs.length && !selectedVideos.length && /^(hi|hlo|hello|hey|good morning|good afternoon|good evening|how are you)[!.,\s]*$/i.test(prompt);
  if (greeting) {
    const greetingAnswer = "Hello! How can I help you today?";
    lastAnswer = greetingAnswer; resultBox.textContent = greetingAnswer;
    assistantBubble.textContent = greetingAnswer; statusBox.textContent = "Done.";
    if (narratorButton) narratorButton.disabled = false;
    buildButton.disabled = false; return;
  }
  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  const timeoutId = controller ? setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS) : null;
  try {
    const images = [];
    for (const file of selectedImages) images.push(await toDataUrl(file));
    for (const file of selectedVideos) images.push(...await extractVideoFrames(file));
    const ocrText = await extractLocalOcr(selectedImages);
    const documentParts = [];
    for (const file of selectedDocs) {
      const content = await toText(file);
      documentParts.push("--- ATTACHED FILE: " + file.name + " ---\n" + content);
    }
    const effectivePrompt = [prompt || (images.length ? "Please analyze the attached images and sampled video frames." : ""), ...documentParts, ...(ocrText ? ["Text extracted from attached images:\n" + ocrText] : []), ...selectedVideos.map(file => "Attached video: " + file.name + ". Images include sampled video frames.")].filter(Boolean).join("\n\n");
    if (effectivePrompt.length > MAX_PROMPT_CHARS) {
      throw new Error("The question and attached text exceed " + MAX_PROMPT_CHARS + " characters. Attach fewer or shorter files.");
    }
    const response = await fetch(API_BASE + "/api/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ prompt: buildQualityPrompt(effectivePrompt), images, ocrText, history: [], memorySummary: "" }),
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
    assistantBubble.textContent = "Sorry, something went wrong: " + resultBox.textContent;
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
    buildButton.disabled = false;
    if (narratorButton) narratorButton.disabled = !lastAnswer;
  }
}

function appendChatMessage(role, text) {
  if (!chatMessages) return { textContent: text };
  const bubble = document.createElement("div");
  bubble.className = "chat-message " + (role === "user" ? "chat-user" : "chat-assistant");
  bubble.textContent = text;
  chatMessages.appendChild(bubble);
  chatMessages.scrollTop = chatMessages.scrollHeight;
  return bubble;
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
async function preprocessImageForOcr(file, rotation = 0) {
  const source = await new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = URL.createObjectURL(file);
  });
  const scale = Math.min(2, 1800 / Math.max(source.naturalWidth, source.naturalHeight));
  const width = Math.max(1, Math.round(source.naturalWidth * scale));
  const height = Math.max(1, Math.round(source.naturalHeight * scale));
  const sideways = rotation % 180 !== 0;
  const canvas = document.createElement("canvas");
  canvas.width = sideways ? height : width;
  canvas.height = sideways ? width : height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.fillStyle = "#fff";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.translate(canvas.width / 2, canvas.height / 2);
  context.rotate(rotation * Math.PI / 180);
  context.drawImage(source, -width / 2, -height / 2, width, height);
  const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
  for (let index = 0; index < pixels.data.length; index += 4) {
    const gray = 0.299 * pixels.data[index] + 0.587 * pixels.data[index + 1] + 0.114 * pixels.data[index + 2];
    const contrast = Math.max(0, Math.min(255, (gray - 128) * 1.65 + 128));
    pixels.data[index] = pixels.data[index + 1] = pixels.data[index + 2] = contrast;
    pixels.data[index + 3] = 255;
  }
  context.putImageData(pixels, 0, 0);
  URL.revokeObjectURL(source.src);
  return canvas;
}

async function extractLocalOcr(files) {
  if (!files?.length || !window.Tesseract?.createWorker) return "";
  let worker;
  let ocrTimeout;
  try {
    worker = await window.Tesseract.createWorker("eng", 1, {
      logger: message => {
        if (message.status === "recognizing text") {
          statusBox.textContent = "Reading image text locally: " + Math.round((message.progress || 0) * 100) + "%";
        }
      }
    });
    ocrTimeout = setTimeout(() => { if (worker) worker.terminate().catch(() => {}); }, 45000);
    const transcripts = [];
    for (let index = 0; index < files.length; index++) {
      const file = files[index];
      let best = { text: "", confidence: 0 };
      for (const rotation of [0, 90, 270]) {
        if (rotation && best.confidence >= 55 && best.text.length >= 12) break;
        const canvas = await preprocessImageForOcr(file, rotation);
        const result = await worker.recognize(canvas);
        const candidate = {
          text: String(result?.data?.text || "").trim(),
          confidence: Number(result?.data?.confidence || 0)
        };
        if (candidate.confidence > best.confidence || candidate.text.length > best.text.length + 8) best = candidate;
      }
      if (best.text) {
        const confidenceNote = best.confidence < 55 ? " [LOW CONFIDENCE — verify against the original image]" : "";
        transcripts.push("IMAGE " + (index + 1) + " (" + Math.round(best.confidence) + "% confidence)" + confidenceNote + ":\n" + best.text);
      }
    }
    return transcripts.join("\n\n").slice(0, 12000);
  } catch (error) {
    console.warn("Local OCR unavailable:", error?.message || error);
    return "";
  } finally {
    clearTimeout(ocrTimeout);
    if (worker) {
      try { await worker.terminate(); } catch (_) {}
    }
  }
}

async function toText(file) {
  const name = file.name.toLowerCase();
  if (name.endsWith(".pdf")) {
    if (!window.pdfjsLib) throw new Error("PDF reader is unavailable. Reload Ask.Me while connected to the internet.");
    window.pdfjsLib.GlobalWorkerOptions.workerSrc = "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
    const pdf = await window.pdfjsLib.getDocument({ data: await file.arrayBuffer() }).promise;
    const pages = Math.min(pdf.numPages, 30);
    let output = "";
    for (let pageNo = 1; pageNo <= pages && output.length < MAX_DOC_CHARS; pageNo++) {
      const page = await pdf.getPage(pageNo);
      const content = await page.getTextContent();
      output += "\n[Page " + pageNo + "] " + content.items.map(item => item.str).join(" ");
    }
    return output.slice(0, MAX_DOC_CHARS);
  }
  if (name.endsWith(".docx")) {
    if (!window.mammoth) throw new Error("DOCX reader is unavailable. Reload Ask.Me while connected to the internet.");
    const result = await window.mammoth.extractRawText({ arrayBuffer: await file.arrayBuffer() });
    return String(result.value || "").slice(0, MAX_DOC_CHARS);
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || "").slice(0, MAX_DOC_CHARS));
    reader.onerror = () => reject(new Error("Could not read attached file: " + file.name));
    reader.readAsText(file);
  });
}
function extractVideoFrames(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file); const video = document.createElement("video");
    video.preload = "metadata"; video.muted = true; video.playsInline = true; video.src = url;
    const cleanup = () => { URL.revokeObjectURL(url); video.removeAttribute("src"); video.load(); };
    video.onerror = () => { cleanup(); reject(new Error("Could not open video " + file.name)); };
    video.onloadedmetadata = async () => {
      try {
        if (!Number.isFinite(video.duration) || video.duration <= 0 || video.duration > 60) throw new Error("Video must be 60 seconds or shorter: " + file.name);
        const canvas = document.createElement("canvas"); const scale = Math.min(1, 1280 / Math.max(video.videoWidth, video.videoHeight));
        canvas.width = Math.max(1, Math.round(video.videoWidth * scale)); canvas.height = Math.max(1, Math.round(video.videoHeight * scale));
        const ctx = canvas.getContext("2d"); const frames = [];
        for (const time of [Math.min(0.05, video.duration * .1), video.duration * .25, video.duration * .5, video.duration * .75, Math.max(0, video.duration - .1)]) {
          await new Promise((ok, fail) => { video.onseeked = ok; video.onerror = fail; video.currentTime = Math.min(time, Math.max(0, video.duration - .05)); });
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height); frames.push(canvas.toDataURL("image/jpeg", .78));
        }
        cleanup(); resolve(frames);
      } catch (error) { cleanup(); reject(error); }
    };
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
