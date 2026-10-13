import { apiJson, currentUser } from './auth.js';

const $ = selector => document.querySelector(selector);
const state = { exams: [], attempts: new Map(), selected: null };

function time(instant) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(instant));
}

function showError(error, target = $('#student-error')) {
  if (error.status === 401) {
    window.location.replace('/login.html');
    return;
  }
  target.textContent = error.message || 'The request could not be completed. Please try again.';
  target.hidden = false;
}

function clearMessages() {
  $('#student-error').hidden = true;
  $('#student-status').hidden = true;
}

function button(label, disabled = false) {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = 'outline-button';
  element.disabled = disabled;
  element.textContent = label;
  return element;
}

function render() {
  const list = $('#catalog-list');
  list.replaceChildren();
  $('#catalog-count').textContent = String(state.exams.length).padStart(2, '0');
  $('#catalog-empty').hidden = state.exams.length > 0;
  $('#catalog-table').hidden = state.exams.length === 0;
  for (const exam of state.exams) {
    const attempt = state.attempts.get(exam.id);
    const terminal = attempt && attempt.status !== 'IN_PROGRESS';
    const card = document.createElement('tr');
    card.className = 'catalog-card';
    card.dataset.examId = String(exam.id);
    const nameCell = document.createElement('td');
    const detailsCell = document.createElement('td');
    const statusCell = document.createElement('td');
    const actionCell = document.createElement('td');
    const status = document.createElement('span');
    status.className = 'catalog-card-state ' + exam.availability.toLowerCase();
    status.textContent = attempt?.status === 'IN_PROGRESS' ? 'In progress'
      : terminal ? (attempt.status === 'SUBMITTED' ? 'Submitted' : 'Time expired')
      : { OPEN: 'Available', UPCOMING: 'Upcoming', CLOSED: 'Closed' }[exam.availability];
    const title = document.createElement('h3');
    title.textContent = exam.title;
    const description = document.createElement('p');
    description.className = 'catalog-card-description';
    description.textContent = exam.description || 'Read the instructions before beginning this examination.';
    const meta = document.createElement('div');
    meta.className = 'catalog-card-meta';
    for (const value of [
      `${exam.questionCount} ${exam.questionCount === 1 ? 'question' : 'questions'}`,
      `${exam.durationMinutes} min`,
      `${exam.maximumMarks} ${exam.maximumMarks === 1 ? 'mark' : 'marks'}`
    ]) {
      const item = document.createElement('span');
      item.textContent = value;
      meta.append(item);
    }
    let action;
    if (terminal) {
      action = button(`View result - ${attempt.score} / ${attempt.totalMarks}`);
      action.addEventListener('click', () => {
        window.location.assign(`/exam.html?id=${encodeURIComponent(attempt.id)}`);
      });
    } else if (attempt?.status === 'IN_PROGRESS') {
      action = button('Resume exam');
      action.addEventListener('click', () => openInstructions(exam));
    } else if (exam.availability === 'OPEN') {
      action = button('Read instructions');
      action.addEventListener('click', () => openInstructions(exam));
    } else if (exam.availability === 'UPCOMING') {
      action = button(`Opens ${time(exam.opensAt)}`, true);
    } else {
      action = button('Closed', true);
    }
    nameCell.append(title, description);
    detailsCell.append(meta);
    statusCell.append(status);
    actionCell.append(action);
    card.append(nameCell, detailsCell, statusCell, actionCell);
    list.append(card);
  }
}

async function refresh() {
  clearMessages();
  $('#refresh-catalog').disabled = true;
  try {
    const [exams, attempts] = await Promise.all([
      apiJson('/api/exams'), apiJson('/api/attempts')
    ]);
    state.exams = exams;
    state.attempts = new Map(attempts.map(attempt => [attempt.examId, attempt]));
    render();
    $('#catalog-loading').hidden = true;
  } catch (error) {
    $('#catalog-loading').hidden = true;
    showError(error);
  } finally {
    $('#refresh-catalog').disabled = false;
  }
}

async function openInstructions(exam) {
  clearMessages();
  try {
    state.selected = await apiJson(`/api/exams/${exam.id}`);
    const attempt = state.attempts.get(exam.id);
    $('#dialog-title').textContent = state.selected.title;
    $('#dialog-description').textContent = state.selected.description || 'Review the rules before beginning.';
    $('#dialog-duration').textContent = `${state.selected.durationMinutes} minutes`;
    $('#dialog-questions').textContent = String(state.selected.questionCount);
    $('#dialog-marks').textContent = String(state.selected.maximumMarks);
    $('#dialog-closes').textContent = time(state.selected.closesAt);
    $('#dialog-error').hidden = true;
    $('#dialog-start').disabled = state.selected.availability !== 'OPEN' && !attempt;
    $('#dialog-start span').textContent = attempt ? 'Resume exam' : 'Start exam';
    $('#exam-dialog').showModal();
  } catch (error) { showError(error); }
}

$('#refresh-catalog').addEventListener('click', refresh);
$('#close-dialog').addEventListener('click', () => $('#exam-dialog').close());
$('#dialog-cancel').addEventListener('click', () => $('#exam-dialog').close());
$('#exam-dialog').addEventListener('click', event => {
  if (event.target === $('#exam-dialog')) $('#exam-dialog').close();
});
$('#dialog-start').addEventListener('click', async () => {
  if (!state.selected) return;
  $('#dialog-error').hidden = true;
  $('#dialog-start').disabled = true;
  try {
    const attempt = await apiJson(`/api/exams/${state.selected.id}/attempts`, { method: 'POST' });
    window.location.assign(`/exam.html?id=${encodeURIComponent(attempt.id)}`);
  } catch (error) {
    $('#dialog-start').disabled = false;
    showError(error, $('#dialog-error'));
    await refresh();
  }
});

async function init() {
  try {
    const user = await currentUser();
    if (!user) { window.location.replace('/login.html'); return; }
    if (user.role !== 'STUDENT') { window.location.replace('/home.html'); return; }
    await refresh();
  } catch (error) { $('#catalog-loading').hidden = true; showError(error); }
}

init();
