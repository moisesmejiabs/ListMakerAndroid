// dashboard.js — full file
// ------------------------------------------------------------
// Defensive helpers
function $(sel) { return document.querySelector(sel); }
function $all(sel) { return Array.from(document.querySelectorAll(sel)); }

async function apiGet(url) {
  console.log("[GET]", url);
  const r = await fetch(url, { credentials: "include" });
  if (!r.ok) throw new Error(`GET ${url} -> ${r.status}`);
  const data = await r.json();
  console.log("[GET][ok]", url, data);
  return data;
}
async function apiPost(url, body) {
  console.log("[POST]", url, body);
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body || {})
  });
  const txt = await r.text();
  let data;
  try { data = txt ? JSON.parse(txt) : {}; } catch { data = { raw: txt }; }
  if (!r.ok) {
    console.error("[POST][err]", url, r.status, txt);
    throw new Error(`POST ${url} -> ${r.status} ${txt}`);
  }
  console.log("[POST][ok]", url, data);
  return data;
}
async function apiPut(url, body) {
  console.log("[PUT]", url, body);
  const r = await fetch(url, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body || {})
  });
  const txt = await r.text();
  let data;
  try { data = txt ? JSON.parse(txt) : {}; } catch { data = { raw: txt }; }
  if (!r.ok) {
    console.error("[PUT][err]", url, r.status, txt);
    throw new Error(`PUT ${url} -> ${r.status} ${txt}`);
  }
  console.log("[PUT][ok]", url, data);
  return data;
}
async function apiDelete(url) {
  console.log("[DELETE]", url);
  const r = await fetch(url, { method: "DELETE", credentials: "include" });
  const txt = await r.text();
  let data;
  try { data = txt ? JSON.parse(txt) : {}; } catch { data = { raw: txt }; }
  if (!r.ok) {
    console.error("[DELETE][err]", url, r.status, txt);
    throw new Error(`DELETE ${url} -> ${r.status} ${txt}`);
  }
  console.log("[DELETE][ok]", url, data);
  return data;
}

// ------------------------------------------------------------
// Global-ish state (kept simple)
let USERS = [];
let QUESTION_SETS = [];

// Optional exports for other pages to reuse
window.DashboardAPI = {
  refreshAll,
  getUsers: () => USERS.slice(),
  getQuestionSets: () => QUESTION_SETS.slice(),
};

// ------------------------------------------------------------
// Users UI wiring (Add / Delete / Modify)
function bindUserForms() {
  // Add User
const addForm = $("#addUserForm");
if (addForm) {
  console.log("[init] addForm found -> attaching submit listener");

  addForm.addEventListener("submit", async (e) => {
    console.log("[addForm] submit event triggered");
    e.preventDefault();

    const name = $("#addName")?.value.trim() || "";
    const phone = $("#addPhone")?.value.trim() || "";
    const address = $("#addAddr")?.value.trim() || "";
    console.log("[addForm] collected values =", { name, phone, address });

    if (!name) {
      console.warn("[addForm] Name is missing, aborting submit");
      alert("Name is required.");
      return;
    }

    try {
      console.log("[addForm] sending apiPost /api/users");
      await apiPost("/api/users", { name, phone, address });
      console.log("[addForm] apiPost success, resetting form");
      addForm.reset();

      console.log("[addForm] refreshing users");
      await refreshUsers();

      console.log("[addForm] User added successfully");
      alert("User added.");
    } catch (err) {
      console.error("[addForm] failed with error:", err);
      alert("Failed to add user.");
    }
  });
} else {
  console.warn("[init] addForm not found in DOM");
}


  // Delete User
const delForm = $("#delUserForm");
if (delForm) {
  delForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = $("#delName")?.value.trim();
    if (!name) { alert("Name is required."); return; }
    try {
      console.log("[DELETE] /api/users?name=" + name);
      await apiDelete(`/api/users?name=${encodeURIComponent(name)}`);
      delForm.reset();
      await refreshUsers();      // refresh USERS + datalist
      updateDeleteSuggestions(); // force rebuild datalist
      alert("User deleted.");
    } catch (err) {
      console.error("[delUserForm] failed", err);
      alert("Failed to delete user.");
    }
  });

  // Pre-fill delete field when USERS list changes
  $("#delName")?.addEventListener("focus", (e) => {
    const u = USERS[0]; // pick the first user for placeholder
    if (u) {
      const field = $("#delName");
      if (field && !field.value) field.placeholder = u.name || "";
    }
  });
  }

  // Modify User
const editForm = $("#editUserForm");
if (editForm) {
  editForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = $("#editName")?.value.trim() || "";
    const phone = $("#editPhone")?.value.trim() || "";
    const address = $("#editAddr")?.value.trim() || "";
    if (!name) { alert("Name is required."); return; }
    try {
      await apiPut("/api/users", { name, phone, address });
      editForm.reset();
      await refreshUsers();
      alert("User updated.");
    } catch (err) {
      console.error("[editUserForm] failed", err);
      alert("Failed to update user.");
    }
  });


    // Pre-fill fields when selection changes (nice-to-have)
        $("#modify-user-select")?.addEventListener("change", (e) => {
          const id = e.target.value;
          const u = USERS.find(u => String(u.id) === String(id));
          if (u) {
            const name = $("#mod-user-name"); const phone = $("#mod-user-phone");
            if (name && !name.value) name.placeholder = u.name || "";
            if (phone && !phone.value) phone.placeholder = u.phone || "";
          }
        });
  }
}
function updateQuestionSuggestions() {
    const dl = document.getElementById("questionNames");
    if (!dl) return;
    dl.innerHTML = "";
    (QUESTIONS || []).forEach(q => {
      const opt = document.createElement("option");
      opt.value = q;
      dl.appendChild(opt);
    });
}
function updateAddSuggestions() {
  const dl = document.getElementById("addUserNames");
  if (!dl) return;
  dl.innerHTML = "";
  USERS.forEach(u => {
    const opt = document.createElement("option");
    opt.value = u.name;
    dl.appendChild(opt);
  });
}

function updateModifySuggestions() {
  const dl = document.getElementById("modUserNames");
  if (!dl) return;
  dl.innerHTML = "";
  USERS.forEach(u => {
    const opt = document.createElement("option");
    opt.value = u.name;
    dl.appendChild(opt);
  });
}

function updateDeleteSuggestions() {
  const dl = document.getElementById("userNames");
  if (!dl) return;
  dl.innerHTML = "";
  USERS.forEach(u => {
    const opt = document.createElement("option");
    opt.value = u.name;
    dl.appendChild(opt);
  });
}

function renderUsersIntoSelects() {
  const usersDiv = $("#usersList");
  if (!usersDiv) return;

  usersDiv.innerHTML = "";

  console.log("[renderUsersIntoSelects] 🔎 USERS length =", USERS.length);

  if (!USERS.length) {
    usersDiv.innerHTML = `<div class="muted small">No users yet.</div>`;
    return;
  }

  USERS.forEach((u, idx) => {
    console.log(`[renderUsersIntoSelects] ➡️ User[${idx}]`, u);

    const row = document.createElement("div");
    row.className = "list-item user-row";
    row.innerHTML = `
      <span class="user-col">${escapeHtml(u.name || "-")}</span>
      <span class="user-col">${escapeHtml(u.phone || "")}</span>
      <span class="user-col">${escapeHtml(u.address || "")}</span>
    `;
    usersDiv.appendChild(row);
  });

  console.log("[renderUsersIntoSelects] ✅ Finished rendering, children count =", usersDiv.children.length);

  // Optional: render a simple table of users if present
  const tableBody = $("#users-table-body");
  if (tableBody) {
    tableBody.innerHTML = "";
    if (!USERS.length) {
      const tr = document.createElement("tr");
      const td = document.createElement("td");
      td.colSpan = 3;
      td.className = "muted";
      td.textContent = "No users yet.";
      tr.appendChild(td);
      tableBody.appendChild(tr);
    } else {
      USERS.forEach(u => {
        const tr = document.createElement("tr");
        tr.innerHTML = `
          <td>${escapeHtml(u.name || "-")}</td>
          <td>${escapeHtml(u.phone || "-")}</td>
          <td class="small muted">${u.id}</td>
        `;
        tableBody.appendChild(tr);
      });
    }
  }
}


async function refreshUsers() {
  try {
    USERS = await apiGet("/api/users");
    renderUsersIntoSelects();
    updateDeleteSuggestions();  // <── add this
    updateModifySuggestions();   // <── add this
    updateAddSuggestions();   // <── add this line
  } catch (err) {
    console.error("refreshUsers failed:", err);
  }
}

async function refreshSession() {
  try {
    const me = await apiGet("/api/session");
    const who = document.getElementById("who");
    if (who) who.textContent = `${me.username} (${me.role})`;
  } catch (err) {
    console.error("refreshSession failed:", err);
    // If unauthorized, force redirect to login
    window.location.href = "/login";
  }
}

// ------------------------------------------------------------
// Question Sets UI (list/create/add/remove)
function bindQuestionSetForms() {
  const createForm = $("#qs-create-form");
  if (createForm) {
    createForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const name = $("#qs-name")?.value.trim();
      if (!name) { alert("Set name is required."); return; }
      try {
        const created = await apiPost("/api/questionsets", { name, questions: [] });
        console.log("[qs] created", created);
        createForm.reset();
        await refreshQuestionSets();
        alert("Question set created.");
      } catch (err) {
        console.error(err); alert("Failed to create question set.");
      }
    });
  }

  // Add Question to a set
  const addQForm = $("#qs-add-question-form");
  if (addQForm) {
    addQForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const setId = $("#qs-select")?.value;
      const q = $("#qs-new-question")?.value.trim();
      if (!setId) { alert("Pick a question set first."); return; }
      if (!q) { alert("Enter a question."); return; }

      const set = QUESTION_SETS.find(s => String(s.id) === String(setId));
      if (!set) { alert("Selected set not found."); return; }

      const questions = (set.questions || []).slice();
      questions.push(q);
      try {
        await apiPut(`/api/questionsets/${encodeURIComponent(setId)}`, { name: set.name, questions });
        addQForm.reset();
        await refreshQuestionSets();
      } catch (err) {
        console.error(err); alert("Failed to add question.");
      }
    });
  }

  // Delete Question
  const delQForm = $("#delQuestionForm");
  if (delQForm) {
    delQForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const text = $("#delQuestion")?.value.trim();
      if (!text) { alert("Question text required."); return; }
      try {
        console.log("[DELETE] /api/questions?text=" + text);
        await apiDelete(`/api/questions?text=${encodeURIComponent(text)}`);
        delQForm.reset();
        await refreshQuestionSets();
        alert("Question deleted.");
      } catch (err) {
        console.error("[delQuestionForm] failed", err);
        alert("Failed to delete question.");
      }
    });
  }

  // Delete a question from a set
  $("#qs-questions-list")?.addEventListener("click", async (e) => {
    const btn = e.target.closest("[data-del-q]");
    if (!btn) return;
    const setId = $("#qs-select")?.value;
    const qIndex = Number(btn.getAttribute("data-del-q"));
    const set = QUESTION_SETS.find(s => String(s.id) === String(setId));
    if (!set) return;
    const questions = (set.questions || []).slice();
    questions.splice(qIndex, 1);
    try {
      await apiPut(`/api/questionsets/${encodeURIComponent(setId)}`, { name: set.name, questions });
      await refreshQuestionSets(setId); // keep selection
    } catch (err) {
      console.error(err); alert("Failed to delete question.");
    }
  });

  // When a set is selected, show its questions
  $("#qs-select")?.addEventListener("change", () => renderSelectedQs());
}

function renderQuestionSetDropdown(keepId) {
  const sel = $("#qs-select");
  if (!sel) return;

  const old = sel.value;
  sel.innerHTML = "";
  const opt0 = document.createElement("option");
  opt0.value = "";
  opt0.textContent = "-- choose a set --";
  sel.appendChild(opt0);

  QUESTION_SETS.forEach(s => {
    const opt = document.createElement("option");
    opt.value = s.id;
    opt.textContent = `${s.name} (${(s.questions || []).length})`;
    sel.appendChild(opt);
  });

  // restore selection if possible
  const desired = keepId ?? old;
  if (desired && QUESTION_SETS.some(s => String(s.id) === String(desired))) {
    sel.value = desired;
  }
  renderSelectedQs();
}

function renderQuestions() {
  console.log("[renderQuestions] QUESTIONS currently =", QUESTIONS);
  const qDiv = $("#questionsList");
  if (!qDiv) return;
  qDiv.innerHTML = "";

  if (!QUESTIONS || QUESTIONS.length === 0) {
    qDiv.innerHTML = `<div class="muted">No questions defined.</div>`;
    return;
  }

  QUESTIONS.forEach((q, idx) => {
    console.log(`[renderQuestions] rendering Q${idx + 1}:`, q);
    const row = document.createElement("div");
    row.className = "list-item";
    row.textContent = q.text || q || `(Question ${idx + 1})`;
    qDiv.appendChild(row);
  });
}

function renderSelectedQs() {
  const wrap = $("#qs-questions-list");
  if (!wrap) return;
  wrap.innerHTML = "";

  const setId = $("#qs-select")?.value;
  const set = QUESTION_SETS.find(s => String(s.id) === String(setId));
  if (!set) {
    wrap.innerHTML = `<div class="muted small">Select a set to see its questions.</div>`;
    return;
  }

  if (!set.questions?.length) {
    wrap.innerHTML = `<div class="muted small">No questions in this set yet.</div>`;
    return;
  }

  set.questions.forEach((q, i) => {
    const row = document.createElement("div");
    row.className = "item-row";
    row.innerHTML = `
      <span class="grow">${escapeHtml(q)}</span>
      <button class="danger" type="button" data-del-q="${i}">Remove</button>
    `;
    wrap.appendChild(row);
  });
}

async function refreshQuestionSets() {
  try {
    console.log("[refreshQuestionSets] fetching /api/questionsets...");
    const sets = await apiGet("/api/questionsets");
    console.log("[refreshQuestionSets] raw sets =", sets);

    if (sets && sets.length > 0) {
      QUESTIONS = sets[0].questions || [];
      console.log("[refreshQuestionSets] QUESTIONS array =", QUESTIONS);
      renderQuestions();
      updateQuestionSuggestions();   // <── populate datalist for delete form
    } else {
      console.warn("[refreshQuestionSets] no sets returned");
      QUESTIONS = [];
      renderQuestions();
      updateQuestionSuggestions();
    }
  } catch (err) {
    console.error("refreshQuestionSets failed:", err);
  }
}


const addQForm = $("#addQuestionForm");
if (addQForm) {
  addQForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const text = $("#newQuestion")?.value.trim();
    if (!text) { alert("Question text required."); return; }
    try {
      await apiPost("/api/questions", { text });
      addQForm.reset();
      await refreshQuestionSets();
      alert("Question added.");
    } catch (err) {
      console.error("[addQuestionForm] failed", err);
      alert("Failed to add question.");
    }
  });
}

// ------------------------------------------------------------
// Utilities
function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[c])
  );
}

// ------------------------------------------------------------
// Entry point
async function refreshAll() {
  await Promise.all([refreshUsers(), refreshQuestionSets()]);
}

function bindAll() {
  bindUserForms();
  bindQuestionSetForms();
  // Add more binders here if/when you add additional sections.
}

// Kickoff
document.addEventListener("DOMContentLoaded", async () => {
  console.log("[dashboard] DOMContentLoaded fired");
  try {
    const r = await fetch("/api/session", { credentials: "include" });
    if (!r.ok) {
      console.warn("[dashboard] ❌ No active session -> redirecting to /login");
      location.href = "/login";
      return;
    }

    bindAll();

    const js = await r.json();
    console.log("[dashboard] ✅ Session =", js);
    document.documentElement.dataset.role = js.role;
    document.getElementById("uname").textContent = js.username;
    document.getElementById("urole").textContent = js.role;

    if (js.role === "admin") {
      console.log("[dashboard] Admin detected -> loading dashboard data...");
      refreshAll();
    } else {
      console.warn("[dashboard] Non-admin user tried to access dashboard -> redirecting");
      location.href = "/userevents";
    }
  } catch (err) {
    console.error("[dashboard] Session fetch failed", err);
    location.href = "/login";
  }
});


