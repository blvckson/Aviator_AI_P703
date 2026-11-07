const promptBox = document.getElementById("prompt");
const imageInput = document.getElementById("image-input");
const fileStatus = document.getElementById("file-status");
const preview = document.getElementById("preview");
const resultBox = document.getElementById("result");
const mediaBox = document.getElementById("media");
const statusBox = document.getElementById("status");
const sourcesBox = document.getElementById("sources");
const sourceList = document.getElementById("source-list");
const buildButton = document.getElementById("build-button");

let selectedImages = [];

const narratorSettingsButton = document.getElementById("narrator-settings-button");
const narratorModal = document.getElementById("narrator-modal");
const narratorClose = document.getElementById("narrator-close");
if (narratorSettingsButton) narratorSettingsButton.addEventListener("click", () => { narratorModal.hidden = false; });
if (narratorClose) narratorClose.addEventListener("click", () => { narratorModal.hidden = true; });

const API_BASE = "https://freeapp-ai.lovesongmelodies.workers.dev";
const attachButton = document.getElementById("attach-button");
if (attachButton) attachButton.addEventListener("click", () => imageInput.click());

imageInput.addEventListener("change", () => {
  selectedImages = Array.from(imageInput.files || []).slice(0, 4);
  preview.replaceChildren();
  fileStatus.textContent = selectedImages.length
    ? selectedImages.length + " image(s) selected"
    : "No image selected";

  for (const file of selectedImages) {
    const img = document.createElement("img");
    img.alt = "Selected screenshot";
    img.className = "preview-image";
    img.src = URL.createObjectURL(file);
    preview.appendChild(img);
  }
});

buildButton.addEventListener("click", askAI);

async function askAI() {
  const prompt = promptBox.value.trim();
  if (!prompt && !selectedImages.length) {
    statusBox.textContent = "Enter a question or attach an image first.";
    return;
  }

  buildButton.disabled = true;
  statusBox.textContent = "Analyzing your request and researching relevant sources...";
  resultBox.textContent = "";
  mediaBox.replaceChildren();
  sourceList.replaceChildren();
  sourcesBox.hidden = true;

  try {
    const images = [];
    for (const file of selectedImages) images.push(await toDataUrl(file));

    const selfModify = ["modify yourself", "upgrade yourself", "fix yourself", "change yourself", "improve yourself", "add yourself"].some(prefix => prompt.toLowerCase().startsWith(prefix));
    if (selfModify) {
      let ownerKey = localStorage.getItem("freeapp_ai_owner_key") || "";
      if (!ownerKey) {
        ownerKey = window.prompt("Enter your Aviator AI owner key:");
        if (ownerKey) localStorage.setItem("freeapp_ai_owner_key", ownerKey);
      }
      if (!ownerKey) throw new Error("Owner key is required for self-modification.");

      statusBox.textContent = "Owner command accepted — inspecting code and preparing the change...";
      const response = await fetch(API_BASE + "/api/self-modify", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-FreeApp-Admin-Key": ownerKey
        },
        body: JSON.stringify({ command: prompt })
      });

      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || "Self-modification failed.");

      resultBox.textContent =
        (data.summary || "Self-modification completed.") +
        (Array.isArray(data.files) && data.files.length
          ? "\\n\\nChanged: " + data.files.map(f => f.path).join(", ")
          : "") +
        (Array.isArray(data.notes) && data.notes.length
          ? "\\n\\nNotes:\\n- " + data.notes.join("\\n- ")
          : "");

      statusBox.textContent = data.changed
        ? "Self-modification committed to main. GitHub Actions should now build and deploy it."
        : "No code change was necessary.";
      return;
    }

    let ocrText = "";
    if (selectedImages.length) {
      statusBox.textContent = "Reading image text on this device...";
      ocrText = await extractLocalOcr(selectedImages);
      if (ocrText) statusBox.textContent = "Local OCR complete — sending text and images for analysis...";
      else statusBox.textContent = "Local OCR is unavailable or found no readable text; trying image analysis...";
    }

    const response = await fetch(API_BASE + "/api/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ prompt, images, ocrText })
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || "The AI service returned an error.");

    resultBox.textContent = data.result || "No response was returned.";
    if (narratorDefaults.auto || narratorDefaults.enabled) {
      if (narratorVoiceId) await playServerNarration(data.result || "No response was returned.");
      else speakText(data.result || "No response was returned.");
    }

    renderMedia(data.media);
    renderSources(data.sources);

    statusBox.textContent = data.researched
      ? "Done — live research was used."
      : "Done.";
  } catch (error) {
    statusBox.textContent = "Request failed.";
    resultBox.textContent = error.message;
  } finally {
    buildButton.disabled = false;
  }
}

function renderSources(sources) {
  if (!Array.isArray(sources)) return;
  for (const source of sources) {
    if (!source?.url) continue;
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
  if (!Array.isArray(media)) return;
  for (const item of media) {
    if (!item?.url) continue;
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
    }

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

function toDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}
