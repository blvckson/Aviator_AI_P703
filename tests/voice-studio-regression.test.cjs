const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

const voiceApp = fs.readFileSync("public/voice/app.js", "utf8");
const chatApp = fs.readFileSync("public/app.js", "utf8");
const chatHtml = fs.readFileSync("public/index.html", "utf8");
const voiceHtml = fs.readFileSync("public/voice/index.html", "utf8");
const worker = fs.readFileSync("public/voice/inference-worker.js", "utf8");
const workflow = fs.readFileSync(".github/workflows/build-apk.yml", "utf8");

test("built-in narrator uses a 0.30x–1.50x range, sentence-aware pacing, stop and both playback engines", () => {
  assert.match(chatApp, /DEFAULT_NARRATOR_RATE\s*=\s*0\.90/);
  assert.match(chatApp, /utterance\.rate\s*=\s*getNarratorRate\(\)/);
  assert.match(chatApp, /const chunks = plainText\.match/);
  assert.match(chatApp, /speechQueue = cleanChunks/);
  assert.match(chatApp, /window\.AskMeNativeTTS\.speakChunk/);
  assert.match(chatApp, /window\.speechSynthesis\.speak/);
  assert.match(chatApp, /window\.speechSynthesis\.cancel/);
  assert.match(chatApp, /askme_narrator_auto_read/);
  assert.match(chatHtml, /id="narrator-speed"[^>]*min="0\.70"[^>]*max="1\.50"[^>]*value="0\.90"/);
  assert.match(chatHtml, /id="narrator-auto-read"/);
});

test("voice studio controller and inference worker expose their ES module entry points", () => {
  assert.match(voiceApp, /class PocketVoice/);
  assert.ok(voiceApp.includes("import { PCMPlayerWorklet"));
  assert.ok(worker.includes("self.onmessage"));
});

test("voice studio exposes microphone recording and audio file upload", () => {
  assert.match(voiceHtml, /id="btn-record"/);
  assert.match(voiceHtml, /id="btn-upload"/);
  assert.match(voiceHtml, /id="file-upload"/);
  assert.ok(voiceHtml.includes("accept=\"audio/*\""));
  assert.match(voiceApp, /new MediaRecorder/);
  assert.match(voiceApp, /getUserMedia/);
  assert.match(voiceApp, /handleFileUpload/);
  assert.match(voiceApp, /processVoiceClip/);
});

test("voice clone requests are encoded locally by the ONNX worker", () => {
  assert.match(voiceApp, /type: "encode_voice"/);
  assert.match(worker, /if \(type === "encode_voice"\)/);
  assert.match(worker, /type: "voice_encoded"/);
  assert.match(voiceApp, /case "voice_encoded"/);
  assert.match(voiceHtml, /ONNX Runtime/);
  assert.match(voiceHtml, /100% Client-Side/);
});

test("voice studio can generate speech, stream audio and stop generation", () => {
  assert.match(voiceApp, /type: "generate"/);
  assert.match(worker, /if \(type === "generate"\)/);
  assert.match(worker, /type: "audio_chunk"/);
  assert.match(voiceApp, /handleAudioChunk/);
  assert.match(voiceApp, /type: "stop"/);
  assert.match(voiceApp, /applyPlaybackSpeed/);
});

test("custom voices are stored and restored using IndexedDB", () => {
  assert.match(voiceApp, /indexedDB\.open/);
  assert.match(voiceApp, /saveVoice\(/);
  assert.match(voiceApp, /loadSavedVoices\(/);
  assert.match(voiceApp, /deleteSavedVoice\(/);
});

test("voice studio speed defaults to 0.90x, supports 0.70x-1.50x and persists safely", () => {
  assert.match(voiceApp, /this\.playbackSpeed = 0\.90/);
  assert.match(voiceHtml, /id="narrator-speed"[^>]*min="0\.70"[^>]*max="1\.50"[^>]*value="0\.90"/);
  assert.match(voiceApp, /askme_voice_studio_playback_speed/);
  assert.match(voiceApp, /Math\.min\(1\.50, Math\.max\(0\.70/);
  assert.match(voiceApp, /this\.player\.playAudio\(this\.applyPlaybackSpeed\(pcm\)\)/);
});

test("APK workflow fails if any required local voice model or runtime asset is missing", () => {
  for (const asset of [
    "bundle.json", "tokenizer.model", "bos_before_voice.npy",
    "mimi_encoder_int8.onnx", "text_conditioner_int8.onnx",
    "flow_lm_main_int8.onnx", "flow_lm_flow_int8.onnx", "mimi_decoder_int8.onnx"
  ]) assert.ok(workflow.includes(asset), "workflow must verify " + asset);
  assert.match(workflow, /Missing or empty voice model asset/);
  assert.match(workflow, /test -s "\$VOICE_DIR\/inference-worker\.js"/);
  assert.match(workflow, /test -s "\$ORT_DIR\/ort-wasm\.wasm"/);
});
