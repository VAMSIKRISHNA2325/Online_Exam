import { apiJson, currentUser } from './auth.js';

const $ = selector => document.querySelector(selector);
const state = { exams: [], exam: null, questions: [], question: null, results: [], active: [] };
let viewId = 0;

function errorMessage(error) {
  if (error.status === 401) {
    window.location.replace('/login.html');
    return 'Your session has ended. Sign in again.';
  }
  return error.message || 'The request could not be completed. Please try again.';
}

function showError(error) {
  $('#desk-error').textContent = typeof error === 'string' ? error : errorMessage(error);
  $('#desk-error').hidden = false;
  $('#desk-status').hidden = true;
  $('#desk-error').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function showStatus(message) {
  $('#desk-status').textContent = message;
  $('#desk-status').hidden = false;
  $('#desk-error').hidden = true;
}

function clearMessages() {
  $('#desk-error').hidden = true;
  $('#desk-status').hidden = true;
}

function localDateTime(instant) {
  const date = new Date(instant);
  const pad = value => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function renderList() {
  const list = $('#exam-list');
  list.replaceChildren();
  $('#exam-count').textContent = String(state.exams.length).padStart(2, '0');
  for (const exam of state.exams) {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'exam-list-item';
    row.setAttribute('aria-current', String(state.exam?.id === exam.id));
    const name = document.createElement('strong');
    name.textContent = exam.title;
    const status = document.createElement('span');
    status.textContent = exam.status === 'PUBLISHED' ? 'Published' : 'Draft';
    row.append(name, status);
    row.addEventListener('click', () => openExam(exam.id));
    list.append(row);
  }
}

async function refreshList() {
  state.exams = await apiJson('/api/instructor/exams');
  renderList();
}

function emptyDesk() {
  viewId++;
  state.exam = null;
  state.questions = [];
  state.results = [];
  state.active = [];
  $('#recovery-section').hidden = true;
  $('#exam-editor').hidden = true;
  $('#desk-empty').hidden = false;
  renderList();
}

function newExam() {
  viewId++;
  clearMessages();
  state.exam = null;
  state.questions = [];
  state.question = null;
  state.results = [];
  state.active = [];
  $('#recovery-section').hidden = true;
  $('#results-section').hidden = true;
  $('#desk-empty').hidden = true;
  $('#exam-editor').hidden = false;
  $('#editor-title').textContent = 'New exam';
  $('#editor-eyebrow').textContent = 'Draft examination';
  $('#exam-status').textContent = 'NEW DRAFT';
  $('#exam-title').value = '';
  $('#exam-description').value = '';
  $('#exam-duration').value = '30';
  $('#exam-opens').value = localDateTime(Date.now() + 60 * 60 * 1000);
  $('#exam-closes').value = localDateTime(Date.now() + 25 * 60 * 60 * 1000);
  $('#paper-section').hidden = true;
  $('#delete-exam').hidden = true;
  $('#exam-form').querySelectorAll('input, textarea').forEach(field => field.disabled = false);
  $('#save-exam').hidden = false;
  renderList();
  $('#exam-title').focus();
}

async function openExam(id) {
  const revision = ++viewId;
  clearMessages();
  try {
    const detail = await apiJson(`/api/instructor/exams/${id}`);
    if (revision !== viewId) return;
    state.exam = detail.exam;
    state.questions = detail.questions;
    state.question = null;
    const published = state.exam.status === 'PUBLISHED';
    $('#desk-empty').hidden = true;
    $('#exam-editor').hidden = false;
    $('#editor-title').textContent = state.exam.title;
    $('#editor-eyebrow').textContent = published ? 'Published examination' : 'Draft examination';
    $('#exam-status').textContent = published ? 'PUBLISHED' : 'DRAFT';
    $('#exam-title').value = state.exam.title;
    $('#exam-description').value = state.exam.description || '';
    $('#exam-duration').value = state.exam.durationMinutes;
    $('#exam-opens').value = localDateTime(state.exam.opensAt);
    $('#exam-closes').value = localDateTime(state.exam.closesAt);
    $('#exam-form').querySelectorAll('input, textarea').forEach(field => field.disabled = published);
    $('#save-exam').hidden = published;
    $('#delete-exam').hidden = published;
    $('#paper-section').hidden = false;
    $('#add-question').hidden = published;
    $('#publish-exam').hidden = published;
    $('#question-editor').hidden = true;
    $('.publish-band').hidden = published;
    $('#recovery-section').hidden = !published;
    $('#results-section').hidden = !published;
    renderQuestions();
    renderList();
    if (published) {
      refreshRecovery();
      refreshResults();
    }
  } catch (error) {
    if (revision === viewId) showError(error);
  }
}

function renderRecovery() {
  const list = $('#recovery-list');
  list.replaceChildren();
  $('#recovery-count').textContent = String(state.active.length).padStart(2, '0');
  $('#recovery-empty').hidden = state.active.length > 0;
  for (const entry of state.active) {
    const row = document.createElement('article');
    row.className = 'result-row';
    const identity = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = entry.displayName;
    const details = document.createElement('span');
    details.textContent = `@${entry.username} · Ends ${new Date(entry.deadlineAt).toLocaleString()}`;
    identity.append(name, details);
    const reset = document.createElement('button');
    reset.type = 'button';
    reset.className = 'outline-button';
    reset.textContent = 'Reset session';
    reset.setAttribute('aria-label', `Reset session for ${entry.displayName}`);
    reset.addEventListener('click', () => recoverSession(entry, reset));
    row.append(identity, reset);
    list.append(row);
  }
}

async function refreshRecovery() {
  if (!state.exam || state.exam.status !== 'PUBLISHED') return;
  const examId = state.exam.id;
  $('#recovery-loading').hidden = false;
  $('#recovery-error').hidden = true;
  $('#refresh-recovery').disabled = true;
  try {
    const attempts = await apiJson(`/api/instructor/exams/${examId}/active-attempts`);
    if (state.exam?.id !== examId) return;
    state.active = attempts;
    renderRecovery();
  } catch (error) {
    if (state.exam?.id !== examId) return;
    $('#recovery-error').textContent = errorMessage(error);
    $('#recovery-error').hidden = false;
  } finally {
    if (state.exam?.id === examId) {
      $('#recovery-loading').hidden = true;
      $('#refresh-recovery').disabled = false;
    }
  }
}

async function recoverSession(entry, button) {
  if (!state.exam) return;
  const examId = state.exam.id;
  const confirmed = window.confirm(`End the current session for ${entry.displayName} (@${entry.username})? Their saved answers and original deadline will remain unchanged.`);
  if (!confirmed) return;
  button.disabled = true;
  clearMessages();
  try {
    await apiJson(`/api/attempts/${entry.attemptId}/session-reset`, { method: 'POST' });
    if (state.exam?.id !== examId) return;
    showStatus(`Session reset for ${entry.displayName}. They can sign in again and continue until the original deadline.`);
    await refreshRecovery();
  } catch (error) {
    if (state.exam?.id === examId) showError(error);
  } finally {
    button.disabled = false;
  }
}

function renderResults() {
  const list = $('#results-list');
  list.replaceChildren();
  $('#result-count').textContent = String(state.results.length).padStart(2, '0');
  $('#results-empty').hidden = state.results.length > 0;
  for (const entry of state.results) {
    const row = document.createElement('article');
    row.className = 'result-row';
    const identity = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = entry.displayName;
    const username = document.createElement('span');
    username.textContent = `@${entry.username}`;
    identity.append(name, username);
    const outcome = document.createElement('div');
    outcome.className = 'result-row-outcome';
    const score = document.createElement('strong');
    score.textContent = `${entry.result.score} / ${entry.result.totalMarks}`;
    const status = document.createElement('span');
    status.textContent = entry.result.status === 'TIMED_OUT' ? 'Time expired' : 'Submitted';
    outcome.append(score, status);
    row.append(identity, outcome);
    list.append(row);
  }
}

async function refreshResults() {
  if (!state.exam || state.exam.status !== 'PUBLISHED') return;
  const examId = state.exam.id;
  $('#results-loading').hidden = false;
  $('#results-error').hidden = true;
  $('#refresh-results').disabled = true;
  try {
    const results = await apiJson(`/api/instructor/exams/${examId}/results`);
    if (state.exam?.id !== examId) return;
    state.results = results;
    renderResults();
  } catch (error) {
    if (state.exam?.id !== examId) return;
    $('#results-error').textContent = errorMessage(error);
    $('#results-error').hidden = false;
  } finally {
    if (state.exam?.id === examId) {
      $('#results-loading').hidden = true;
      $('#refresh-results').disabled = false;
    }
  }
}

function renderQuestions() {
  const list = $('#question-list');
  list.replaceChildren();
  $('#question-count').textContent = String(state.questions.length).padStart(2, '0');
  $('#paper-empty').hidden = state.questions.length > 0;
  const published = state.exam.status === 'PUBLISHED';
  for (const question of [...state.questions].sort((a, b) => a.displayOrder - b.displayOrder)) {
    const card = document.createElement('article');
    card.className = 'question-card';
    const number = document.createElement('span');
    number.className = 'question-number';
    number.textContent = `QUESTION ${String(question.displayOrder + 1).padStart(2, '0')}`;
    const title = document.createElement('h3');
    title.textContent = question.text;
    const meta = document.createElement('p');
    meta.className = 'question-meta';
    meta.textContent = `${question.marks} ${question.marks === 1 ? 'mark' : 'marks'} · ${question.options.length} options`;
    const answer = question.options.find(option => option.correct);
    const key = document.createElement('p');
    key.className = 'question-key';
    key.textContent = `Key: ${answer?.text || 'No correct answer selected'}`;
    card.append(number, title, meta, key);
    if (!published) {
      const actions = document.createElement('div');
      actions.className = 'question-actions';
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.textContent = 'Edit';
      edit.addEventListener('click', () => editQuestion(question));
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.textContent = 'Delete';
      remove.addEventListener('click', () => deleteQuestion(question));
      actions.append(edit, remove);
      card.append(actions);
    }
    list.append(card);
  }
}

function updateOptionControls() {
  const rows = [...$('#options-list').children];
  rows.forEach((row, index) => {
    row.querySelector('input[type="radio"]').value = String(index);
    row.querySelector('input[type="radio"]').setAttribute('aria-label', `Mark option ${index + 1} as correct`);
    row.querySelector('input[type="text"]').setAttribute('aria-label', `Option ${index + 1}`);
    row.querySelector('button').disabled = rows.length <= 2;
  });
  $('#add-option').disabled = rows.length >= 6;
}

function addOption(text = '', correct = false) {
  if ($('#options-list').children.length >= 6) return;
  const row = document.createElement('div');
  row.className = 'option-row';
  const radio = document.createElement('input');
  radio.type = 'radio';
  radio.name = 'correct-option';
  radio.setAttribute('aria-label', 'Correct answer');
  radio.checked = correct;
  const input = document.createElement('input');
  input.type = 'text';
  input.maxLength = 5000;
  input.required = true;
  input.placeholder = 'Answer option';
  input.value = text;
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.textContent = 'Remove';
  remove.addEventListener('click', () => {
    row.remove();
    updateOptionControls();
  });
  row.append(radio, input, remove);
  $('#options-list').append(row);
  updateOptionControls();
}

function editQuestion(question = null) {
  clearMessages();
  state.question = question;
  $('#question-editor').hidden = false;
  $('#question-editor-title').textContent = question ? 'Edit question' : 'New question';
  $('#question-text').value = question?.text || '';
  $('#question-marks').value = question?.marks || 1;
  $('#options-list').replaceChildren();
  if (question) {
    for (const option of [...question.options].sort((a, b) => a.displayOrder - b.displayOrder)) {
      addOption(option.text, option.correct);
    }
  } else {
    addOption();
    addOption();
  }
  $('#question-editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
  $('#question-text').focus();
}

async function deleteQuestion(question) {
  if (!window.confirm('Delete this draft question?')) return;
  try {
    await apiJson(`/api/questions/${question.id}`, { method: 'DELETE' });
    await openExam(state.exam.id);
    showStatus('Question deleted.');
  } catch (error) { showError(error); }
}

$('#new-exam').addEventListener('click', newExam);
$('#refresh-recovery').addEventListener('click', refreshRecovery);
$('#refresh-results').addEventListener('click', refreshResults);
$('#empty-create').addEventListener('click', newExam);
$('#exam-form').addEventListener('submit', async event => {
  event.preventDefault();
  clearMessages();
  const opens = new Date($('#exam-opens').value);
  const closes = new Date($('#exam-closes').value);
  if (!Number.isFinite(opens.getTime()) || !Number.isFinite(closes.getTime()) || closes <= opens) {
    showError('Set a closing time after the opening time.');
    return;
  }
  const body = {
    title: $('#exam-title').value.trim(),
    description: $('#exam-description').value.trim(),
    durationMinutes: Number($('#exam-duration').value),
    opensAt: opens.toISOString(),
    closesAt: closes.toISOString()
  };
  const id = state.exam?.id;
  $('#save-exam').disabled = true;
  try {
    const saved = await apiJson(id ? `/api/exams/${id}` : '/api/exams', {
      method: id ? 'PUT' : 'POST', body
    });
    await refreshList();
    await openExam(saved.id);
    showStatus(id ? 'Exam details saved.' : 'Draft created. Add your questions below.');
  } catch (error) { showError(error); }
  finally { $('#save-exam').disabled = false; }
});

$('#delete-exam').addEventListener('click', async () => {
  if (!state.exam || !window.confirm('Delete this draft exam and all its questions?')) return;
  try {
    await apiJson(`/api/exams/${state.exam.id}`, { method: 'DELETE' });
    await refreshList();
    emptyDesk();
    showStatus('Draft exam deleted.');
  } catch (error) { showError(error); }
});

$('#add-question').addEventListener('click', () => editQuestion());
$('#cancel-question').addEventListener('click', () => { $('#question-editor').hidden = true; });
$('#add-option').addEventListener('click', () => addOption());
$('#question-form').addEventListener('submit', async event => {
  event.preventDefault();
  clearMessages();
  const rows = [...$('#options-list').children];
  const selected = rows.findIndex(row => row.querySelector('input[type="radio"]').checked);
  if (selected < 0) { showError('Choose one correct answer.'); return; }
  const options = rows.map((row, index) => ({
    text: row.querySelector('input[type="text"]').value.trim(),
    correct: index === selected,
    displayOrder: index
  }));
  if (options.some(option => !option.text)) { showError('Enter text for every answer option.'); return; }
  const body = {
    text: $('#question-text').value.trim(),
    marks: Number($('#question-marks').value),
    displayOrder: state.question?.displayOrder ?? state.questions.length,
    options
  };
  const id = state.question?.id;
  $('#save-question').disabled = true;
  try {
    await apiJson(id ? `/api/questions/${id}` : `/api/exams/${state.exam.id}/questions`, {
      method: id ? 'PUT' : 'POST', body
    });
    await openExam(state.exam.id);
    showStatus(id ? 'Question saved.' : 'Question added to the draft.');
  } catch (error) { showError(error); }
  finally { $('#save-question').disabled = false; }
});

$('#publish-exam').addEventListener('click', async () => {
  if (!state.exam) return;
  clearMessages();
  $('#publish-exam').disabled = true;
  try {
    await apiJson(`/api/exams/${state.exam.id}/publish`, { method: 'POST' });
    await refreshList();
    await openExam(state.exam.id);
    showStatus('Exam published. Its questions, key, marks and timing are now fixed.');
  } catch (error) { showError(error); }
  finally { $('#publish-exam').disabled = false; }
});

async function init() {
  try {
    const user = await currentUser();
    if (!user) { window.location.replace('/login.html'); return; }
    if (user.role !== 'INSTRUCTOR') { window.location.replace('/home.html'); return; }
    await refreshList();
    if (state.exams.length) await openExam(state.exams[0].id);
    else emptyDesk();
    $('#desk-loading').hidden = true;
    $('#new-exam').disabled = false;
  } catch (error) {
    $('#desk-loading').hidden = true;
    showError(error);
  }
}

init();
