const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];

const state = {
  view: "dashboard",
  overview: null,
  files: [],
  current: null,
};

function toast(msg) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.remove("hidden");
  setTimeout(() => el.classList.add("hidden"), 2400);
}

async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const type = res.headers.get("content-type") || "";
  if (!type.includes("application/json")) {
    if (!res.ok) throw new Error("请求失败 " + res.status);
    return res;
  }
  const json = await res.json();
  if (json.ret !== 1) throw new Error(json.msg || "请求失败");
  return json.data;
}

function fmtBytes(n) {
  const v = Number(n) || 0;
  if (v < 1024) return v + " B";
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
  if (v < 1024 * 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + " MB";
  return (v / 1024 / 1024 / 1024).toFixed(2) + " GB";
}

function fmtTime(sec) {
  if (!sec) return "-";
  const d = new Date(Number(sec) * 1000);
  return d.toLocaleString();
}

function isImage(mime, name) {
  return (mime || "").startsWith("image/") || /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(name || "");
}

function fileUrl(file, extra = "") {
  const q = new URLSearchParams({ bucket: file.bucket, filename: file.filename });
  if (extra) q.set(extra, "1");
  return "/get?" + q.toString();
}

function setView(name) {
  state.view = name;
  $$(".nav-btn").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
  $("#view-dashboard").classList.toggle("hidden", name !== "dashboard");
  $("#view-files").classList.toggle("hidden", name !== "files");
  $("#view-volumes").classList.toggle("hidden", name !== "volumes");
  $("#page-title").textContent = { dashboard: "总览", files: "文件与元数据", volumes: "Volume" }[name];
}

function renderOverview() {
  const ov = state.overview || { fileCount: 0, bucketCount: 0, totalBytes: 0, buckets: [], volumes: [] };
  const used = (ov.volumes || []).reduce((s, v) => s + (v.usedBytes || 0), 0);
  const cap = (ov.volumes || []).reduce((s, v) => s + (v.maxSize || 0), 0);
  $("#stat-cards").innerHTML = [
    ["文件数", ov.fileCount],
    ["Bucket", ov.bucketCount],
    ["对象体积", fmtBytes(ov.totalBytes)],
    ["卷占用", fmtBytes(used) + " / " + fmtBytes(cap)],
  ].map(([label, value]) => `<article class="card"><div class="label">${label}</div><div class="value">${value}</div></article>`).join("");

  const body = $("#bucket-body");
  body.innerHTML = (ov.buckets || []).map((b) =>
    `<tr><td>${esc(b.name)}</td><td>${b.fileCount}</td><td>${fmtBytes(b.totalBytes)}</td></tr>`
  ).join("") || `<tr><td colspan="3" class="empty">暂无 bucket</td></tr>`;

  const sel = $("#bucket-filter");
  const cur = sel.value;
  sel.innerHTML = `<option value="">全部 bucket</option>` + (ov.buckets || [])
    .map((b) => `<option value="${esc(b.name)}">${esc(b.name)}</option>`).join("");
  sel.value = cur;

  $("#volume-list").innerHTML = (ov.volumes || []).map((v) => {
    const pct = v.maxSize ? Math.min(100, (v.usedBytes / v.maxSize) * 100) : 0;
    return `<article class="panel">
      <h3>Volume ${v.id} ${v.readOnly ? "(只读)" : ""}</h3>
      <div class="bar"><span style="width:${pct.toFixed(1)}%"></span></div>
      <p>${fmtBytes(v.usedBytes)} / ${fmtBytes(v.maxSize)} · 文件 ${v.fileCount}</p>
      <p class="mono">${esc(v.path || "")}</p>
    </article>`;
  }).join("");
}

function renderFiles() {
  const rows = state.files.map((f) => {
    const thumb = isImage(f.mime, f.filename)
      ? `<img class="thumb" src="${fileUrl(f)}" alt="">`
      : `<span class="mono">·</span>`;
    return `<tr>
      <td>${thumb}</td>
      <td>${esc(f.bucket)}</td>
      <td>${esc(f.filename)}</td>
      <td class="mono">${esc(f.mime || "")}</td>
      <td>${fmtBytes(f.size)}</td>
      <td>${f.vid}</td>
      <td class="mono">${esc(String(f.key))}</td>
      <td>${fmtTime(f.created)}</td>
      <td><button class="ghost" data-bucket="${esc(f.bucket)}" data-filename="${esc(f.filename)}">详情</button></td>
    </tr>`;
  }).join("");
  $("#file-body").innerHTML = rows;
  $("#file-empty").classList.toggle("hidden", state.files.length > 0);
  $$("#file-body [data-bucket]").forEach((btn) => {
    btn.addEventListener("click", () => openFile(btn.dataset.bucket, btn.dataset.filename));
  });
}

function esc(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function openFile(bucket, filename) {
  const file = state.files.find((f) => f.bucket === bucket && f.filename === filename);
  if (!file) return;
  state.current = file;
  $("#drawer").classList.remove("hidden");
  const preview = $("#preview");
  if (isImage(file.mime, file.filename)) {
    preview.innerHTML = `<img src="${fileUrl(file)}" alt="">`;
  } else {
    preview.innerHTML = `<p class="mono">二进制对象，无内嵌预览</p>`;
  }
  $("#meta-dl").innerHTML = [
    ["bucket", file.bucket],
    ["filename", file.filename],
    ["mime", file.mime || ""],
    ["size", fmtBytes(file.size) + " (" + file.size + ")"],
    ["vid", file.vid],
    ["key", file.key],
    ["cookie", file.cookie],
    ["created", fmtTime(file.created)],
  ].map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join("");
  $("#meta-form").newFilename.value = file.filename;
  $("#meta-form").mime.value = file.mime || "";
  $("#download-link").href = fileUrl(file, "download");
}

async function loadAll() {
  state.overview = await api("/overview");
  const params = new URLSearchParams();
  const bucket = $("#bucket-filter").value;
  const q = $("#search").value.trim();
  if (bucket) params.set("bucket", bucket);
  if (q) params.set("q", q);
  state.files = await api("/list?" + params.toString()) || [];
  renderOverview();
  renderFiles();
}

$$(".nav-btn").forEach((btn) => btn.addEventListener("click", () => setView(btn.dataset.view)));
$("#refresh-btn").addEventListener("click", () => loadAll().catch((e) => toast(e.message)));
$("#bucket-filter").addEventListener("change", () => loadAll().catch((e) => toast(e.message)));
$("#search").addEventListener("input", debounce(() => loadAll().catch((e) => toast(e.message)), 250));

$("#drawer-close").addEventListener("click", () => $("#drawer").classList.add("hidden"));
$("#upload-open").addEventListener("click", () => $("#upload-modal").classList.remove("hidden"));
$("#upload-cancel").addEventListener("click", () => $("#upload-modal").classList.add("hidden"));

$("#file-input").addEventListener("change", (e) => {
  const f = e.target.files[0];
  if (!f) return;
  const form = $("#upload-form");
  if (!form.filename.value) form.filename.value = f.name;
  if (!form.mime.value) form.mime.value = f.type || "application/octet-stream";
  $("#drop-label").textContent = f.name + " · " + fmtBytes(f.size);
});

$("#upload-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target;
  const file = $("#file-input").files[0];
  if (!file) return toast("请选择文件");
  const bucket = form.bucket.value.trim();
  const filename = (form.filename.value || file.name).trim();
  const mime = form.mime.value || file.type || "application/octet-stream";
  const q = new URLSearchParams({ bucket, filename, mime });
  try {
    await api("/upload?" + q.toString(), { method: "POST", body: file });
    $("#upload-modal").classList.add("hidden");
    form.reset();
    form.bucket.value = "default";
    $("#drop-label").textContent = "选择或拖入文件";
    toast("上传成功");
    setView("files");
    await loadAll();
  } catch (err) {
    toast(err.message);
  }
});

$("#meta-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const file = state.current;
  if (!file) return;
  const q = new URLSearchParams({
    bucket: file.bucket,
    filename: file.filename,
    newFilename: e.target.newFilename.value.trim(),
    mime: e.target.mime.value.trim(),
  });
  try {
    await api("/meta?" + q.toString(), { method: "POST" });
    toast("元数据已保存");
    await loadAll();
    const updatedName = e.target.newFilename.value.trim();
    openFile(file.bucket, updatedName);
  } catch (err) {
    toast(err.message);
  }
});

$("#delete-btn").addEventListener("click", async () => {
  const file = state.current;
  if (!file || !confirm("确认删除 " + file.bucket + "/" + file.filename + " ?")) return;
  const q = new URLSearchParams({ bucket: file.bucket, filename: file.filename });
  try {
    await api("/del?" + q.toString(), { method: "POST" });
    $("#drawer").classList.add("hidden");
    toast("已删除");
    await loadAll();
  } catch (err) {
    toast(err.message);
  }
});

function debounce(fn, ms) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

loadAll().catch((e) => toast(e.message));
