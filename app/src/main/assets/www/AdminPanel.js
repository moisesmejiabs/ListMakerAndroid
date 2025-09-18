// AdminPanel.js — matches API: GET -> ["Q1","Q2"], POST {question:"..."}

async function getJSON(url) {
  const r = await fetch(url, { credentials: 'include' });
  if (!r.ok) throw new Error(`${url} -> ${r.status}`);
  return r.json();
}
async function postJSON(url, data) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(data)
  });
  if (!r.ok) throw new Error(`${url} -> ${r.status}`);
  return r.json();
}

let questions = [];

function renderList() {
  const list = document.getElementById('question-list');
  if (!list) return;
  list.innerHTML = '';
  questions.forEach(q => {
    const li = document.createElement('li');
    li.textContent = q; // simple string
    list.appendChild(li);
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  try {
    questions = await getJSON('/api/questions'); // ["Q1","Q2",...]
    renderList();
  } catch (e) {
    console.error('[adminpanel] load failed', e);
  }

  const form = document.getElementById('question-form');
  if (form) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const inp = document.getElementById('label');
      const q = (inp?.value || '').trim();
      if (!q) return;
      try {
        questions = await postJSON('/api/questions', { question: q }); // returns full list
        renderList();
        if (inp) inp.value = '';
        alert('Question added.');
      } catch (err) {
        console.error('[adminpanel] add failed', err);
        alert('Add failed.');
      }
    });
  }
});
