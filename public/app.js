const promptBox=document.getElementById("prompt");
const fileInput=document.getElementById("file-input");
const fileStatus=document.getElementById("file-status");
const preview=document.getElementById("preview");
const resultBox=document.getElementById("result");
const mediaBox=document.getElementById("media");
const statusBox=document.getElementById("status");
const sourcesBox=document.getElementById("sources");
const sourceList=document.getElementById("source-list");
const buildButton=document.getElementById("build-button");
const API_BASE="https://freeapp-ai.lovesongmelodies.workers.dev";
const MAX_FILES=5,MAX_FILE_BYTES=12*1024*1024,MAX_VIDEO_SECONDS=60;
let selectedFiles=[];
const narratorEnabled=document.getElementById("narrator-enabled");
const narratorSpeed=document.getElementById("narrator-speed");
const narratorSpeedValue=document.getElementById("narrator-speed-value");
const narratorStatus=document.getElementById("narrator-status");
const savedEnabled=localStorage.getItem("askme_narrator_enabled")==="true";
const savedSpeed=Number(localStorage.getItem("askme_narrator_speed")||"0.90");
narratorEnabled.checked=savedEnabled;
narratorSpeed.value=String(Math.min(1.5,Math.max(.3,Number.isFinite(savedSpeed)?savedSpeed:.9)));
function updateSpeedLabel(){narratorSpeedValue.value=Number(narratorSpeed.value).toFixed(2)+"×";}
updateSpeedLabel();
narratorEnabled.addEventListener("change",()=>{localStorage.setItem("askme_narrator_enabled",String(narratorEnabled.checked));if(!narratorEnabled.checked)stopNarration();});
narratorSpeed.addEventListener("input",updateSpeedLabel);
narratorSpeed.addEventListener("change",()=>localStorage.setItem("askme_narrator_speed",narratorSpeed.value));
document.getElementById("narrator-stop").addEventListener("click",stopNarration);
function stopNarration(){if("speechSynthesis"in window)window.speechSynthesis.cancel();narratorStatus.textContent="Narration stopped.";}
function speakAnswer(text){
  if(!narratorEnabled.checked||!text)return;
  try{
    if(!("speechSynthesis"in window)||typeof SpeechSynthesisUtterance==="undefined")throw new Error("Text-to-speech is not supported by this browser.");
    window.speechSynthesis.cancel();
    const utterance=new SpeechSynthesisUtterance(text);
    utterance.rate=Number(narratorSpeed.value);
    utterance.onstart=()=>{narratorStatus.textContent="Reading answer aloud…";};
    utterance.onend=()=>{narratorStatus.textContent="Finished reading.";};
    utterance.onerror=event=>{narratorStatus.textContent="Narrator unavailable: "+(event.error||"speech error");};
    window.speechSynthesis.speak(utterance);
  }catch(error){narratorStatus.textContent="Narrator unavailable: "+error.message;}
}
const voiceModal=document.getElementById("voice-modal");
document.getElementById("voice-studio-button").addEventListener("click",()=>{voiceModal.hidden=false;});
document.getElementById("voice-close").addEventListener("click",()=>{voiceModal.hidden=true;});
document.getElementById("attach-button").addEventListener("click",()=>fileInput.click());
fileInput.addEventListener("change",()=>{
  selectedFiles=Array.from(fileInput.files||[]).slice(0,MAX_FILES);
  preview.replaceChildren();
  fileStatus.textContent=selectedFiles.length?selectedFiles.length+" file(s) selected":"No files selected";
  for(const file of selectedFiles){
    const item=document.createElement("div");item.className="file-chip";
    const name=document.createElement("span");name.textContent=file.name+" ("+formatBytes(file.size)+")";
    item.appendChild(name);
    if(file.type.startsWith("image/")){const img=document.createElement("img");img.alt="Image preview";img.src=URL.createObjectURL(file);item.prepend(img);}
    else if(file.type.startsWith("video/")){const tag=document.createElement("span");tag.textContent="▶ VIDEO";item.prepend(tag);}
    else{const tag=document.createElement("span");tag.textContent="▤ DOCUMENT";item.prepend(tag);}
    preview.appendChild(item);
  }
});
function formatBytes(n){return n<1024*1024?Math.round(n/1024)+" KB":(n/1024/1024).toFixed(1)+" MB";}
buildButton.addEventListener("click",askAI);
async function askAI(){
  const originalPrompt=promptBox.value.trim();
  if(!originalPrompt&&!selectedFiles.length){statusBox.textContent="Enter a question or attach a file first.";return;}
  if(selectedFiles.some(f=>f.size>MAX_FILE_BYTES)){statusBox.textContent="One or more files exceed the 12 MB per-file limit.";return;}
  buildButton.disabled=true;statusBox.textContent="Preparing your files…";resultBox.textContent="";mediaBox.replaceChildren();sourceList.replaceChildren();sourcesBox.hidden=true;
  try{
    const selfModify=["modify yourself","upgrade yourself","fix yourself","change yourself","improve yourself","add yourself"].some(prefix=>originalPrompt.toLowerCase().startsWith(prefix));
    if(selfModify){
      let ownerKey=localStorage.getItem("freeapp_ai_owner_key")||"";
      if(!ownerKey){ownerKey=window.prompt("Enter your Ask.Me owner key:");if(ownerKey)localStorage.setItem("freeapp_ai_owner_key",ownerKey);}
      if(!ownerKey)throw new Error("Owner key is required for self-modification.");
      statusBox.textContent="Owner command accepted — preparing the change…";
      const response=await fetch(API_BASE+"/api/self-modify",{method:"POST",headers:{"Content-Type":"application/json","X-FreeApp-Admin-Key":ownerKey},body:JSON.stringify({command:originalPrompt})});
      const data=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(data.error||"Self-modification failed.");
      resultBox.textContent=(data.summary||"Self-modification completed.")+(Array.isArray(data.files)&&data.files.length?"\n\nChanged: "+data.files.map(f=>f.path).join(", "):"");
      statusBox.textContent=data.changed?"Self-modification committed. GitHub Actions should build the update.":"No code change was necessary.";
      return;
    }
    const images=[],extracted=[];
    for(let i=0;i<selectedFiles.length;i++){
      const file=selectedFiles[i];statusBox.textContent="Processing "+file.name+" ("+(i+1)+"/"+selectedFiles.length+")…";
      if(file.type.startsWith("image/")){images.push(await imageToDataUrl(file));}
      else if(file.type.startsWith("video/")){const frames=await extractVideoFrames(file);images.push(...frames);extracted.push("Video: "+file.name+" — sampled "+frames.length+" frames for visual analysis.");}
      else if(file.type==="application/pdf"||file.name.toLowerCase().endsWith(".pdf")){extracted.push(await extractPdfText(file));}
      else if(file.name.toLowerCase().endsWith(".docx")){extracted.push(await extractDocxText(file));}
      else if(file.type.startsWith("text/")||/\.(txt|md|csv)$/i.test(file.name)){extracted.push("Document: "+file.name+"\n"+(await file.text()).slice(0,30000));}
      else throw new Error("Unsupported file type: "+file.name);
    }
    let ocrText="";
    const imageFiles=selectedFiles.filter(f=>f.type.startsWith("image/"));
    if(imageFiles.length){statusBox.textContent="Reading image text on this device…";ocrText=await extractLocalOcr(imageFiles);}
    const prompt=[originalPrompt,extracted.filter(Boolean).join("\n\n"),ocrText?"Text extracted from attached images:\n"+ocrText:""].filter(Boolean).join("\n\n");
    if(prompt.length>45000)throw new Error("Extracted content is too large. Please attach fewer or smaller files.");
    statusBox.textContent="Sending your question and prepared attachments…";
    const response=await fetch(API_BASE+"/api/generate",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({prompt,images,ocrText})});
    const data=await response.json().catch(()=>({}));
    if(!response.ok)throw new Error(data.error||"The AI service returned an error.");
    const answer=String(data.result||"No response was returned.");resultBox.textContent=answer;
    // Narration is isolated: speech errors must never turn a successful answer into a failed request.
    speakAnswer(answer);
    renderMedia(data.media);renderSources(data.sources);
    statusBox.textContent=data.researched?"Done — live research was used.":"Done.";
  }catch(error){statusBox.textContent="Request failed.";resultBox.textContent=error.message;}
  finally{buildButton.disabled=false;}
}
async function imageToDataUrl(file){
  const source=await loadImage(URL.createObjectURL(file));
  const scale=Math.min(1,1600/Math.max(source.naturalWidth,source.naturalHeight));
  const canvas=document.createElement("canvas");canvas.width=Math.max(1,Math.round(source.naturalWidth*scale));canvas.height=Math.max(1,Math.round(source.naturalHeight*scale));
  canvas.getContext("2d").drawImage(source,0,0,canvas.width,canvas.height);
  return canvas.toDataURL("image/jpeg",.82);
}
function loadImage(url){return new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>{URL.revokeObjectURL(url);resolve(img);};img.onerror=()=>{URL.revokeObjectURL(url);reject(new Error("Could not read image attachment."));};img.src=url;});}
async function extractPdfText(file){
  if(!window.pdfjsLib)throw new Error("PDF reader failed to load. Check your internet connection and retry.");
  pdfjsLib.GlobalWorkerOptions.workerSrc="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";
  const pdf=await pdfjsLib.getDocument({data:await file.arrayBuffer()}).promise;let text="Document: "+file.name+"\n";
  const pages=Math.min(pdf.numPages,40);
  for(let n=1;n<=pages;n++){const page=await pdf.getPage(n);const content=await page.getTextContent();text+="\n[Page "+n+"] "+content.items.map(x=>x.str).join(" ");if(text.length>30000)break;}
  if(pdf.numPages>pages)text+="\n[Only first "+pages+" pages extracted.]";
  return text.slice(0,30000);
}
async function extractDocxText(file){
  if(!window.mammoth)throw new Error("DOCX reader failed to load. Check your internet connection and retry.");
  const result=await mammoth.extractRawText({arrayBuffer:await file.arrayBuffer()});
  return "Document: "+file.name+"\n"+String(result.value||"").slice(0,30000);
}
async function extractVideoFrames(file){
  const url=URL.createObjectURL(file),video=document.createElement("video");
  video.preload="metadata";video.muted=true;video.playsInline=true;video.src=url;
  try{
    await new Promise((resolve,reject)=>{video.onloadedmetadata=resolve;video.onerror=()=>reject(new Error("Could not open video: "+file.name));});
    if(!Number.isFinite(video.duration)||video.duration<=0)throw new Error("Video duration could not be read.");
    if(video.duration>MAX_VIDEO_SECONDS)throw new Error("Video "+file.name+" is longer than 60 seconds. Trim it and retry.");
    const canvas=document.createElement("canvas");const scale=Math.min(1,1280/Math.max(video.videoWidth,video.videoHeight));canvas.width=Math.max(1,Math.round(video.videoWidth*scale));canvas.height=Math.max(1,Math.round(video.videoHeight*scale));
    const ctx=canvas.getContext("2d");const times=[0,video.duration*.25,video.duration*.5,video.duration*.75,Math.max(0,video.duration-.15)];
    const frames=[];
    for(const time of [...new Set(times.map(t=>Math.min(t,Math.max(0,video.duration-.05))))]){
      await seekVideo(video,time);ctx.drawImage(video,0,0,canvas.width,canvas.height);frames.push(canvas.toDataURL("image/jpeg",.78));
    }
    return frames.slice(0,5);
  }finally{URL.revokeObjectURL(url);video.removeAttribute("src");video.load();}
}
function seekVideo(video,time){return new Promise((resolve,reject)=>{const done=()=>{video.removeEventListener("seeked",done);video.removeEventListener("error",fail);resolve();};const fail=()=>{video.removeEventListener("seeked",done);reject(new Error("Could not sample video frame."));};video.addEventListener("seeked",done,{once:true});video.addEventListener("error",fail,{once:true});video.currentTime=time;});}
async function preprocessImageForOcr(file,rotation=0){
  const url=URL.createObjectURL(file),source=await loadImage(url);
  const scale=Math.min(2,1800/Math.max(source.naturalWidth,source.naturalHeight));
  const width=Math.max(1,Math.round(source.naturalWidth*scale)),height=Math.max(1,Math.round(source.naturalHeight*scale));
  const sideways=rotation%180!==0,canvas=document.createElement("canvas");
  canvas.width=sideways?height:width;canvas.height=sideways?width:height;
  const ctx=canvas.getContext("2d",{willReadFrequently:true});ctx.fillStyle="#fff";ctx.fillRect(0,0,canvas.width,canvas.height);
  ctx.translate(canvas.width/2,canvas.height/2);ctx.rotate(rotation*Math.PI/180);ctx.drawImage(source,-width/2,-height/2,width,height);
  const pixels=ctx.getImageData(0,0,canvas.width,canvas.height);
  for(let i=0;i<pixels.data.length;i+=4){const gray=.299*pixels.data[i]+.587*pixels.data[i+1]+.114*pixels.data[i+2];const v=Math.max(0,Math.min(255,(gray-128)*1.65+128));pixels.data[i]=pixels.data[i+1]=pixels.data[i+2]=v;pixels.data[i+3]=255;}
  ctx.putImageData(pixels,0,0);return canvas;
}
async function extractLocalOcr(files){
  if(!window.Tesseract?.createWorker)return "";
  let worker;
  try{
    worker=await Tesseract.createWorker("eng",1,{logger:m=>{if(m.status==="recognizing text")statusBox.textContent="Reading image text: "+Math.round((m.progress||0)*100)+"%";}});
    const output=[];
    for(let i=0;i<files.length;i++){
      let best={text:"",confidence:0};
      for(const rotation of [0,90,270]){
        if(rotation&&best.confidence>=55&&best.text.length>=12)break;
        const canvas=await preprocessImageForOcr(files[i],rotation),result=await worker.recognize(canvas);
        const candidate={text:String(result?.data?.text||"").trim(),confidence:Number(result?.data?.confidence||0)};
        if(candidate.confidence>best.confidence||candidate.text.length>best.text.length+8)best=candidate;
      }
      if(best.text)output.push("IMAGE "+(i+1)+" ("+Math.round(best.confidence)+"% confidence)"+(best.confidence<55?" [LOW CONFIDENCE — verify against original]":"")+":\n"+best.text);
    }
    return output.join("\n\n").slice(0,12000);
  }catch(error){console.warn("OCR unavailable:",error);return "";}
  finally{if(worker)try{await worker.terminate();}catch(_){}}
}
function renderSources(sources){if(!Array.isArray(sources))return;for(const source of sources){if(!source?.url)continue;const li=document.createElement("li"),a=document.createElement("a");a.href=source.url;a.target="_blank";a.rel="noopener noreferrer";a.textContent=source.title||source.url;li.appendChild(a);sourceList.appendChild(li);}sourcesBox.hidden=sourceList.children.length===0;}
function renderMedia(media){if(!Array.isArray(media))return;for(const item of media){if(!item?.url)continue;const wrap=document.createElement("div");wrap.className="media-card";if(item.type==="image"){const img=document.createElement("img");img.src=item.url;img.alt=item.title||"Relevant image";img.loading="lazy";wrap.appendChild(img);}else if(item.type==="video"){const link=document.createElement("a");link.href=item.url;link.target="_blank";link.rel="noopener noreferrer";link.textContent="▶ "+(item.title||"Watch relevant video");wrap.appendChild(link);}if(item.title){const caption=document.createElement("div");caption.textContent=item.title;wrap.appendChild(caption);}mediaBox.appendChild(wrap);}}
