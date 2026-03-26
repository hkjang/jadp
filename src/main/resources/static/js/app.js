const state = {
  currentJobId: null,
  pollHandle: null,
};

const els = {
  form: document.getElementById("convert-form"),
  fileInput: document.getElementById("file"),
  selectedFile: document.getElementById("selected-file"),
  dropzone: document.getElementById("dropzone"),
  statusText: document.getElementById("status-text"),
  debugOutput: document.getElementById("debug-output"),
  markdownOutput: document.getElementById("markdown-output"),
  jsonOutput: document.getElementById("json-output"),
  htmlPreview: document.getElementById("html-preview"),
  fileList: document.getElementById("file-list"),
  recentJobs: document.getElementById("recent-jobs"),
  runAsync: document.getElementById("run-async"),
  runSync: document.getElementById("run-sync"),
  tabs: document.querySelectorAll(".tab"),
  panes: {
    markdown: document.getElementById("pane-markdown"),
    json: document.getElementById("pane-json"),
    html: document.getElementById("pane-html"),
    files: document.getElementById("pane-files"),
  },
  piiFileInput: document.getElementById("pii-file"),
  piiSelectedFile: document.getElementById("pii-selected-file"),
  piiDropzone: document.getElementById("pii-dropzone"),
  piiStatusText: document.getElementById("pii-status-text"),
  piiSummaryOutput: document.getElementById("pii-summary-output"),
  piiMaskedFile: document.getElementById("pii-masked-file"),
  piiFindings: document.getElementById("pii-findings"),
  piiDebugOutput: document.getElementById("pii-debug-output"),
  runPiiDetect: document.getElementById("run-pii-detect"),
  runPiiMask: document.getElementById("run-pii-mask"),
};

async function init() {
  wireTabs();
  wireDropzone(els.dropzone, els.fileInput, updateSelectedFile);
  wireDropzone(els.piiDropzone, els.piiFileInput, updatePiiSelectedFile);
  wireFileInput();
  wirePiiControls();
  wireButtons();
  await loadOptions();
  await refreshJobs();
  setStatus("대기 중");
  setPiiStatus("대기 중");
}

function wireTabs() {
  els.tabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      const target = tab.dataset.tab;
      els.tabs.forEach((node) => node.classList.toggle("active", node === tab));
      Object.entries(els.panes).forEach(([key, pane]) => {
        pane.hidden = key !== target;
      });
    });
  });
}

function wireDropzone(dropzone, input, onUpdate) {
  ["dragenter", "dragover"].forEach((eventName) => {
    dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      dropzone.classList.add("dragover");
    });
  });

  ["dragleave", "drop"].forEach((eventName) => {
    dropzone.addEventListener(eventName, (event) => {
      event.preventDefault();
      dropzone.classList.remove("dragover");
    });
  });

  dropzone.addEventListener("drop", (event) => {
    const files = event.dataTransfer.files;
    if (files && files.length > 0) {
      input.files = files;
      onUpdate();
    }
  });
}

function wireFileInput() {
  els.fileInput.addEventListener("change", updateSelectedFile);
  document.getElementById("reset-form").addEventListener("click", () => {
    clearResults();
    setStatus("초기화됨");
  });
}

function wirePiiControls() {
  els.piiFileInput.addEventListener("change", updatePiiSelectedFile);
  document.getElementById("reset-pii-form").addEventListener("click", () => {
    clearPiiResults();
    setPiiStatus("초기화됨");
  });
  els.runPiiDetect.addEventListener("click", async () => {
    await submitPii("/api/v1/pii/detect", "detect");
  });
  els.runPiiMask.addEventListener("click", async () => {
    await submitPii("/api/v1/pii/mask", "mask");
  });
}

function updateSelectedFile() {
  const file = els.fileInput.files?.[0];
  els.selectedFile.textContent = file
    ? `${file.name} (${Math.round(file.size / 1024)} KB)`
    : "선택된 파일이 없습니다.";
}

function updatePiiSelectedFile() {
  const file = els.piiFileInput.files?.[0];
  els.piiSelectedFile.textContent = file
    ? `${file.name} (${Math.round(file.size / 1024)} KB)`
    : "선택된 파일이 없습니다.";
}

function wireButtons() {
  els.runAsync.addEventListener("click", async () => {
    await submitForm("/api/v1/pdf/convert", "async");
  });
  els.runSync.addEventListener("click", async () => {
    await submitForm("/api/v1/pdf/convert-sync", "sync");
  });
}

async function loadOptions() {
  try {
    const response = await fetch("/api/v1/pdf/config/options");
    const data = await response.json();
    fillSelect("readingOrder", data.readingOrders, data.defaults.readingOrder);
    fillSelect("tableMethod", data.tableMethods, data.defaults.tableMethod);
    fillSelect("imageOutput", data.imageOutputs, data.defaults.imageOutput);
    fillSelect("imageFormat", data.imageFormats, data.defaults.imageFormat);
    fillSelect("hybrid", data.hybridBackends, data.defaults.hybrid);
    fillSelect("hybridMode", data.hybridModes, data.defaults.hybridMode);
  } catch (error) {
    writeDebug({ stage: "loadOptions", error: error.message });
  }
}

function fillSelect(id, values, defaultValue) {
  const select = document.getElementById(id);
  select.innerHTML = "";
  values.forEach((value) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = value;
    option.selected = value === defaultValue;
    select.appendChild(option);
  });
}

function buildFormData() {
  const formData = new FormData();
  const file = els.fileInput.files?.[0];
  if (!file) {
    throw new Error("PDF 파일을 먼저 선택하세요.");
  }

  formData.append("file", file);
  [
    "formats",
    "password",
    "pages",
    "readingOrder",
    "tableMethod",
    "imageOutput",
    "imageFormat",
    "imageDir",
    "hybrid",
    "hybridMode",
    "hybridUrl",
    "hybridTimeout",
  ].forEach((field) => {
    const value = document.getElementById(field).value;
    if (value !== "") {
      formData.append(field, value);
    }
  });

  [
    "sanitize",
    "keepLineBreaks",
    "includeHeaderFooter",
    "useStructTree",
    "hybridFallback",
  ].forEach((field) => {
    formData.append(field, String(document.getElementById(field).checked));
  });

  return formData;
}

function buildPiiFormData() {
  const formData = new FormData();
  const file = els.piiFileInput.files?.[0];
  if (!file) {
    throw new Error("파일을 먼저 선택하세요.");
  }
  formData.append("file", file);
  return formData;
}

async function submitForm(url, mode) {
  try {
    clearResults();
    setStatus(mode === "async" ? "비동기 Job 생성 중..." : "동기 변환 실행 중...");
    const formData = buildFormData();
    const response = await fetch(url, {
      method: "POST",
      body: formData,
    });

    const body = await response.json().catch(() => ({}));
    writeDebug({ url, mode, status: response.status, request: Object.fromEntries(formData), body });

    if (!response.ok) {
      throw new Error(body.message || `요청 실패 (${response.status})`);
    }

    if (mode === "sync") {
      renderSync(body);
      setStatus(`동기 변환 완료: ${body.status}`);
      await refreshJobs();
      return;
    }

    state.currentJobId = body.jobId;
    setStatus(`Job 생성 완료: ${body.jobId}`);
    startPolling(body.jobId);
    await refreshJobs();
  } catch (error) {
    setStatus(`오류: ${error.message}`);
    writeDebug({ error: error.message });
  }
}

async function submitPii(url, mode) {
  try {
    clearPiiResults();
    setPiiStatus(mode === "mask" ? "masked 파일 생성 중..." : "PII 탐지 중...");
    const formData = buildPiiFormData();
    const response = await fetch(url, {
      method: "POST",
      body: formData,
    });

    const body = await response.json().catch(() => ({}));
    writePiiDebug({
      url,
      mode,
      status: response.status,
      file: els.piiFileInput.files?.[0]?.name,
      body,
    });

    if (!response.ok) {
      throw new Error(body.message || `요청 실패 (${response.status})`);
    }

    renderPiiSummary(body, mode);
    renderPiiFindings(body.findings || []);
    if (mode === "mask") {
      renderMaskedFile(body);
      setPiiStatus(`masked 파일 생성 완료: ${body.maskedFilename}`);
    } else {
      setPiiStatus(`PII 탐지 완료: ${body.findingCount}건`);
    }
  } catch (error) {
    setPiiStatus(`오류: ${error.message}`);
    writePiiDebug({ error: error.message });
  }
}

function renderSync(payload) {
  els.markdownOutput.textContent = payload.markdown || "Markdown 결과 없음";
  els.jsonOutput.textContent = payload.jsonSummary || "JSON 결과 없음";
  els.htmlPreview.src = payload.htmlPreviewUrl || "about:blank";
  renderFiles(payload.outputFiles || []);
}

function startPolling(jobId) {
  if (state.pollHandle) {
    clearInterval(state.pollHandle);
  }
  state.pollHandle = setInterval(async () => {
    try {
      const response = await fetch(`/api/v1/pdf/jobs/${jobId}`);
      const payload = await response.json();
      renderJob(payload);
      if (payload.status === "SUCCEEDED" || payload.status === "FAILED") {
        clearInterval(state.pollHandle);
        state.pollHandle = null;
        await refreshJobs();
      }
    } catch (error) {
      setStatus(`폴링 실패: ${error.message}`);
      clearInterval(state.pollHandle);
      state.pollHandle = null;
    }
  }, 2000);
}

function renderJob(job) {
  setStatus(`Job ${job.jobId}: ${job.status}`);
  if (job.status === "SUCCEEDED") {
    renderFiles(job.files || []);
    const markdown = (job.files || []).find((file) => file.format === "markdown");
    const json = (job.files || []).find((file) => file.format === "json");
    const html = (job.files || []).find((file) => file.format === "html");
    els.markdownOutput.textContent = markdown ? `다운로드 또는 미리보기: ${markdown.downloadUrl}` : "Markdown 결과 없음";
    els.jsonOutput.textContent = json ? `다운로드 또는 미리보기: ${json.downloadUrl}` : "JSON 결과 없음";
    els.htmlPreview.src = html ? html.downloadUrl : "about:blank";
  } else if (job.status === "FAILED") {
    els.markdownOutput.textContent = job.error || "실패";
    els.jsonOutput.textContent = job.error || "실패";
    els.htmlPreview.src = "about:blank";
  }
  writeDebug({ polledJob: job });
}

function renderFiles(files) {
  if (!files.length) {
    els.fileList.innerHTML = '<div class="small muted">생성 파일이 없습니다.</div>';
    return;
  }
  els.fileList.innerHTML = files
    .map((file) => `
      <div class="file-row">
        <div>
          <span class="pill">${file.format}</span>
          <strong>${file.filename}</strong>
          <div class="small">${file.relativePath} · ${file.contentType} · ${file.size} bytes</div>
        </div>
        <a class="link-chip" href="${file.downloadUrl}" target="_blank" rel="noreferrer">열기</a>
      </div>
    `)
    .join("");
}

function renderPiiSummary(payload, mode) {
  const lines = [
    `documentId: ${payload.documentId}`,
    `filename: ${payload.originalFilename}`,
    `mediaType: ${payload.mediaType || "unknown"}`,
    `pageCount: ${payload.pageCount ?? "-"}`,
    `findingCount: ${payload.findingCount ?? 0}`,
  ];
  if (mode === "mask") {
    lines.push(`maskedFileId: ${payload.maskedFileId}`);
    lines.push(`maskedFilename: ${payload.maskedFilename}`);
  }
  els.piiSummaryOutput.textContent = lines.join("\n");
}

function renderMaskedFile(payload) {
  if (!payload.maskedDownloadUrl) {
    els.piiMaskedFile.innerHTML = '<div class="small muted">아직 masked 파일이 없습니다.</div>';
    return;
  }
  els.piiMaskedFile.innerHTML = `
    <div class="file-row">
      <div>
        <span class="pill">masked</span>
        <strong>${payload.maskedFilename}</strong>
        <div class="small">${payload.contentType || "application/octet-stream"}</div>
      </div>
      <a class="link-chip" href="${payload.maskedDownloadUrl}" target="_blank" rel="noreferrer">다운로드</a>
    </div>
  `;
}

function renderPiiFindings(findings) {
  if (!findings.length) {
    els.piiFindings.innerHTML = '<div class="small muted">탐지 결과가 없습니다.</div>';
    return;
  }
  els.piiFindings.innerHTML = findings
    .map((finding) => `
      <div class="finding-row">
        <div>
          <span class="pill">${finding.type}</span>
          <strong>${finding.label}</strong>
          <div class="small">원문: ${finding.originalText}</div>
          <div class="small">마스킹: ${finding.maskedText}</div>
          <div class="small">page ${finding.pageNumber} · x=${finding.boundingBox.x}, y=${finding.boundingBox.y}, w=${finding.boundingBox.width}, h=${finding.boundingBox.height}</div>
          <div class="small">source: ${finding.detectionSource}</div>
        </div>
      </div>
    `)
    .join("");
}

async function refreshJobs() {
  try {
    const response = await fetch("/api/v1/pdf/jobs?page=0&size=8");
    const payload = await response.json();
    const jobs = payload.jobs || [];
    if (!jobs.length) {
      els.recentJobs.innerHTML = '<div class="small muted">아직 Job 이력이 없습니다.</div>';
      return;
    }
    els.recentJobs.innerHTML = jobs
      .map((job) => `
        <div class="job-row">
          <div>
            <strong>${job.sourceFilename}</strong>
            <div class="small">Job ${job.jobId}</div>
            <div class="small">${job.status} · ${job.requestedFormats.join(", ")}</div>
          </div>
          <button class="secondary" type="button" onclick="loadJob('${job.jobId}')">상세 보기</button>
        </div>
      `)
      .join("");
  } catch (error) {
    els.recentJobs.innerHTML = `<div class="small muted">목록을 불러오지 못했습니다: ${error.message}</div>`;
  }
}

async function loadJob(jobId) {
  try {
    const response = await fetch(`/api/v1/pdf/jobs/${jobId}`);
    const payload = await response.json();
    renderJob(payload);
  } catch (error) {
    setStatus(`Job 조회 실패: ${error.message}`);
  }
}

function clearResults() {
  els.markdownOutput.textContent = "아직 결과가 없습니다.";
  els.jsonOutput.textContent = "아직 결과가 없습니다.";
  els.htmlPreview.src = "about:blank";
  els.fileList.innerHTML = '<div class="small muted">생성 파일이 없습니다.</div>';
}

function clearPiiResults() {
  els.piiSummaryOutput.textContent = "아직 탐지 결과가 없습니다.";
  els.piiMaskedFile.innerHTML = '<div class="small muted">아직 masked 파일이 없습니다.</div>';
  els.piiFindings.innerHTML = '<div class="small muted">아직 탐지 결과가 없습니다.</div>';
}

function setStatus(message) {
  els.statusText.textContent = message;
}

function setPiiStatus(message) {
  els.piiStatusText.textContent = message;
}

function writeDebug(data) {
  els.debugOutput.textContent = JSON.stringify(data, null, 2);
}

function writePiiDebug(data) {
  els.piiDebugOutput.textContent = JSON.stringify(data, null, 2);
}

window.loadJob = loadJob;
window.addEventListener("DOMContentLoaded", init);
