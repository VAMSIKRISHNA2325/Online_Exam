import { apiJson, currentUser } from './auth.js';

const $ = selector => document.querySelector(selector);
const attemptId = Number(new URLSearchParams(window.location.search).get('id'));
const state = { attempt: null, questions: [], index: 0, offset: 0,
  pending: [], processing: false, refreshing: false, submitting: false,
  resultLoading: false, result: null, resultError: '', timeReached: false };

function readableTime(instant) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
    .format(new Date(instant));
}

function showGlobalError(message) {
  $('#attempt-error').textContent = message;
  $('#attempt-error').hidden = false;
  $('#attempt-loading').hidden = true;
}

function showSaveError(message) {
  $('#save-error').textContent = message;
  $('#save-error').hidden = false;
}

function clearSaveError() {
  $('#save-error').hidden = true;
}

function remainingSeconds() {
  if (!state.attempt) return 0;
  return Math.max(0, Math.ceil((Date.parse(state.attempt.deadlineAt)
    - (Date.now() + state.offset)) / 1000));
}

function updateTimer() {
  if (!state.attempt) return;
  if (state.result || state.attempt.status !== 'IN_PROGRESS') {
    $('#timer').textContent = 'FINISHED';
    $('.timer-box span').textContent = 'ATTEMPT STATUS';
    $('.timer-box').classList.remove('urgent');
    return;
  }
  const seconds = remainingSeconds();
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  $('#timer').textContent = hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
  $('.timer-box').classList.toggle('urgent', seconds <= 300);
  if (seconds === 0 && !state.timeReached) {
    state.timeReached = true;
    render();
    loadResult();
  }
}

function canAnswer() {
  return state.attempt?.status === 'IN_PROGRESS' && !state.timeReached;
}

function hasUnsaved() {
  return state.questions.some(question => question.saveState === 'unsaved');
}

function renderNavigation() {
  const list = $('#question-navigation');
  list.replaceChildren();
  for (const [index, question] of state.questions.entries()) {
    const control = document.createElement('button');
    control.type = 'button';
    control.textContent = String(index + 1).padStart(2, '0');
    control.setAttribute('aria-label', `Question ${index + 1}${question.selectedOptionId == null ? ', unanswered' : ', saved answer'}`);
    control.setAttribute('aria-current', String(index === state.index));
    if (question.selectedOptionId != null) control.classList.add('answered');
    if (question.saveState === 'saving') control.classList.add('saving');
    if (question.saveState === 'unsaved' || question.saveState === 'conflict') control.classList.add('unsaved');
    control.addEventListener('click', () => { state.index = index; render(); });
    list.append(control);
  }
  const answered = state.questions.filter(question => question.selectedOptionId != null).length;
  $('#exam-progress').textContent = `${answered} of ${state.questions.length} saved answers`;
}

function renderQuestion() {
  const question = state.questions[state.index];
  if (!question) return;
  $('#question-number').textContent = `QUESTION ${String(state.index + 1).padStart(2, '0')} / ${String(state.questions.length).padStart(2, '0')}`;
  $('#question-marks').textContent = `${question.marks} ${question.marks === 1 ? 'MARK' : 'MARKS'}`;
  $('#question-title').textContent = question.text;
  const list = $('#answer-options');
  list.replaceChildren();
  for (const option of [...question.options].sort((a, b) => a.displayOrder - b.displayOrder)) {
    const row = document.createElement('label');
    row.className = 'answer-option';
    const input = document.createElement('input');
    input.type = 'radio';
    input.name = 'answer';
    input.value = String(option.id);
    input.checked = question.draftSelection === option.id;
    input.disabled = !canAnswer();
    if (input.checked) row.classList.add('selected');
    input.addEventListener('change', () => queueSelection(question, option.id));
    const text = document.createElement('span');
    text.textContent = option.text;
    row.append(input, text);
    list.append(row);
  }
  $('#clear-answer').disabled = !canAnswer() || question.draftSelection == null;
  const status = $('#save-state');
  status.className = 'answer-save-state';
  if (question.saveState === 'saving') {
    status.textContent = 'Saving…';
    status.classList.add('saving');
  } else if (question.saveState === 'unsaved') {
    status.textContent = 'Not saved';
    status.classList.add('unsaved');
  } else if (question.saveState === 'conflict') {
    status.textContent = 'Changed in another tab';
    status.classList.add('conflict');
  } else {
    status.textContent = question.selectedOptionId == null
      ? (question.revision === 0 ? 'Not answered' : `Cleared · saved revision ${question.revision}`)
      : `Saved · revision ${question.revision}`;
  }
  $('#retry-save').hidden = question.saveState !== 'unsaved' || !canAnswer();
  $('#previous-question').disabled = state.index === 0;
  $('#next-question').disabled = state.index >= state.questions.length - 1;
}

function renderTerminal() {
  const terminal = state.attempt.status !== 'IN_PROGRESS' || state.timeReached || !!state.result;
  $('.exam-header-label').textContent = terminal ? 'Examination result' : 'Examination in progress';
  $('.exam-sidebar .section-number').textContent = terminal ? 'Result summary' : 'Question navigation';
  $('.exam-content-top').hidden = terminal;
  $('#question-panel').hidden = terminal;
  $('#exam-navigation').hidden = terminal;
  $('#submission-note').hidden = terminal;
  $('#question-navigation').hidden = terminal;
  $('#exam-progress').hidden = terminal;
  $('#terminal-panel').hidden = !terminal;
  if (!terminal) return;
  if ($('#submit-dialog').open) $('#submit-dialog').close();
  const result = state.result;
  $('#result-summary').hidden = !result;
  $('#retry-result').hidden = !!result || state.resultLoading;
  if (result) {
    $('#terminal-panel h2').textContent = result.status === 'TIMED_OUT'
      ? 'Time is up. Your work is graded.' : 'Your examination is complete.';
    $('#terminal-panel p').textContent = 'This score was calculated from your saved answers.';
    $('#result-score').textContent = `${result.score} / ${result.totalMarks}`;
    $('#result-percentage').textContent = `${Number(result.percentage).toFixed(2)}%`;
    $('#result-status').textContent = result.status === 'TIMED_OUT' ? 'Time expired' : 'Submitted';
    $('#result-finalized').textContent = `Finalized ${readableTime(result.finalizedAt)}`;
  } else {
    $('#terminal-panel h2').textContent = state.timeReached
      ? 'The deadline has been reached.' : 'Finalizing your examination.';
    $('#terminal-panel p').textContent = state.resultError
      || 'Your saved work is being graded by the server. You can check again shortly.';
    $('#result-finalized').textContent = '';
  }
}

function renderSubmission() {
  const pendingSave = state.processing || state.pending.length
    || state.questions.some(question => question.saveState === 'saving');
  const unsaved = hasUnsaved();
  $('#submit-attempt').disabled = !canAnswer() || pendingSave || unsaved || state.submitting;
  $('#submission-note').textContent = pendingSave ? 'Saving your answer before submission...'
    : unsaved ? 'An answer is not saved. Retry it before submitting.'
      : 'Only saved answers count toward your score.';
}

function render() {
  const active = document.activeElement;
  const focusedQuestion = active?.closest('#question-navigation')
    ? [...$('#question-navigation').children].indexOf(active) : -1;
  const focusedAnswer = active?.matches('#answer-options input[type="radio"]')
    ? active.value : null;
  renderNavigation();
  renderQuestion();
  renderSubmission();
  renderTerminal();
  if (focusedQuestion >= 0 && !$('#question-navigation').hidden) {
    $('#question-navigation').children[focusedQuestion]?.focus({ preventScroll: true });
  } else if (focusedAnswer && !$('#question-panel').hidden) {
    [...document.querySelectorAll('#answer-options input[type="radio"]')]
      .find(input => input.value === focusedAnswer)?.focus({ preventScroll: true });
  }
}

function showResult(result) {
  state.result = result;
  state.resultError = '';
  state.attempt = { ...state.attempt, status: result.status,
    score: result.score, totalMarks: result.totalMarks, finalizedAt: result.finalizedAt };
  state.timeReached = true;
  state.pending = [];
  render();
  updateTimer();
}

async function loadResult() {
  if (state.result || state.resultLoading || !state.attempt) return;
  state.resultLoading = true;
  state.resultError = '';
  render();
  try {
    showResult(await apiJson(`/api/attempts/${attemptId}/result`));
  } catch (error) {
    if (error.status === 401) { window.location.replace('/login.html'); return; }
    state.resultError = error.code === 'RESULT_NOT_READY'
      ? 'The server has not reached the deadline yet. Checking the original time again.'
      : (error.message || 'The result could not be loaded. Check again.');
    if (error.code === 'RESULT_NOT_READY') {
      setTimeout(() => { if (!state.result) refreshFromServer(); }, 1500);
    }
  } finally {
    state.resultLoading = false;
    render();
  }
}

function queueSelection(question, selection) {
  if (!canAnswer()) return;
  clearSaveError();
  question.draftSelection = selection;
  question.saveState = 'saving';
  state.pending = state.pending.filter(item => item.questionId !== question.id);
  state.pending.push({ questionId: question.id, selection });
  render();
  processQueue();
}

async function processQueue() {
  if (state.processing) return;
  state.processing = true;
  let reload = false;
  while (state.pending.length) {
    const operation = state.pending.shift();
    const question = state.questions.find(item => item.id === operation.questionId);
    if (!question) continue;
    try {
      const saved = await apiJson(`/api/attempts/${attemptId}/answers/${question.id}`, {
        method: 'PUT', body: {
          selectedOptionId: operation.selection, expectedRevision: question.revision
        }
      });
      question.selectedOptionId = saved.selectedOptionId;
      question.revision = saved.revision;
      const queued = state.pending.some(item => item.questionId === question.id);
      question.saveState = queued ? 'saving' : 'saved';
      if (!queued) question.draftSelection = saved.selectedOptionId;
    } catch (error) {
      if (error.code === 'STALE_REVISION' && error.current) {
        question.selectedOptionId = error.current.selectedOptionId;
        question.revision = error.current.revision;
        question.draftSelection = error.current.selectedOptionId;
        question.saveState = 'conflict';
        showSaveError('This answer changed in another tab. The latest saved choice is shown. Review it, then choose again if needed.');
      } else {
        question.saveState = 'unsaved';
        const disconnected = error instanceof TypeError || error.name === 'AbortError';
        showSaveError(disconnected
          ? 'Connection interrupted. This answer is not saved. Reconnect and retry before the deadline.'
          : (error.message || 'Your answer was not saved. Retry before the deadline.'));
      }
      for (const waiting of state.pending) {
        const item = state.questions.find(candidate => candidate.id === waiting.questionId);
        if (item) item.saveState = 'unsaved';
      }
      state.pending = [];
      if (error.code === 'ATTEMPT_EXPIRED' || error.code === 'ATTEMPT_FINALIZED') {
        state.timeReached = true;
        reload = true;
      }
      render();
      break;
    }
    render();
  }
  state.processing = false;
  if (navigator.onLine && !hasUnsaved()) {
    $('#connection-notice').hidden = true;
  }
  render();
  if (reload) loadResult();
}

function applyAttempt(attempt) {
  const questionId = state.questions[state.index]?.id;
  state.attempt = attempt;
  state.offset = Date.parse(attempt.serverNow) - Date.now();
  state.questions = [...attempt.questions].sort((a, b) => a.displayOrder - b.displayOrder)
    .map(question => ({ ...question, draftSelection: question.selectedOptionId, saveState: 'saved' }));
  const index = state.questions.findIndex(question => question.id === questionId);
  state.index = Math.max(0, index);
  state.timeReached = remainingSeconds() === 0;
  $('#deadline-info').textContent = readableTime(attempt.deadlineAt);
  $('#attempt-loading').hidden = true;
  $('#exam-layout').hidden = false;
  render();
  updateTimer();
  if (attempt.status !== 'IN_PROGRESS' || state.timeReached) loadResult();
}

async function refreshFromServer() {
  if (state.processing || state.pending.length || state.refreshing
      || hasUnsaved()) return;
  state.refreshing = true;
  try {
    const attempt = await apiJson(`/api/attempts/${attemptId}`);
    applyAttempt(attempt);
    clearSaveError();
  } catch (error) {
    if (error.status === 401) window.location.replace('/login.html');
    else showGlobalError(error.message || 'The examination could not be loaded. Try refreshing.');
  } finally {
    state.refreshing = false;
  }
}

window.addEventListener('offline', () => {
  $('#connection-notice').textContent = 'You appear to be offline. Answers cannot be saved until your connection returns.';
  $('#connection-notice').hidden = false;
});
window.addEventListener('online', () => {
  const unsaved = hasUnsaved();
  $('#connection-notice').textContent = unsaved
    ? 'Connection restored. Retry each unsaved answer before submitting.' : '';
  $('#connection-notice').hidden = !unsaved;
  if (!unsaved && state.attempt) refreshFromServer();
});
window.addEventListener('beforeunload', event => {
  if (canAnswer() && (state.processing || state.pending.length
      || hasUnsaved())) {
    event.preventDefault();
    event.returnValue = '';
  }
});

$('#submit-attempt').addEventListener('click', () => {
  if ($('#submit-attempt').disabled) return;
  const saved = state.questions.filter(question => question.selectedOptionId != null).length;
  $('#submit-summary').textContent = `${saved} of ${state.questions.length} questions have a saved answer.`;
  $('#submit-dialog-error').hidden = true;
  $('#submit-dialog').showModal();
});
$('#cancel-submit').addEventListener('click', () => $('#submit-dialog').close());
$('#submit-dialog').addEventListener('cancel', event => {
  if (state.submitting) event.preventDefault();
});
$('#confirm-submit').addEventListener('click', async () => {
  if (state.submitting || $('#submit-attempt').disabled) return;
  state.submitting = true;
  $('#confirm-submit').disabled = true;
  $('#cancel-submit').disabled = true;
  $('#submit-dialog-error').hidden = true;
  render();
  try {
    showResult(await apiJson(`/api/attempts/${attemptId}/submit`, { method: 'POST' }));
  } catch (error) {
    if (error.status === 401) { window.location.replace('/login.html'); return; }
    if (error.code === 'ATTEMPT_EXPIRED' || error.code === 'ATTEMPT_FINALIZED') {
      $('#submit-dialog').close();
      await refreshFromServer();
    } else {
      $('#submit-dialog-error').textContent = error.message
        || 'Submission could not be confirmed. Try again; repeated submission is safe.';
      $('#submit-dialog-error').hidden = false;
    }
  } finally {
    state.submitting = false;
    $('#confirm-submit').disabled = false;
    $('#cancel-submit').disabled = false;
    render();
  }
});
$('#retry-result').addEventListener('click', loadResult);

$('#clear-answer').addEventListener('click', () => {
  const question = state.questions[state.index];
  if (question) queueSelection(question, null);
});
$('#retry-save').addEventListener('click', () => {
  const question = state.questions[state.index];
  if (question?.saveState === 'unsaved') queueSelection(question, question.draftSelection);
});
$('#previous-question').addEventListener('click', () => {
  if (state.index > 0) { state.index--; render(); }
});
$('#next-question').addEventListener('click', () => {
  if (state.index < state.questions.length - 1) { state.index++; render(); }
});
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && state.attempt) refreshFromServer();
});
window.addEventListener('focus', () => {
  if (state.attempt) refreshFromServer();
});
setInterval(updateTimer, 1000);

async function init() {
  if (!Number.isSafeInteger(attemptId) || attemptId <= 0) {
    showGlobalError('The examination link is invalid. Return to your student desk.');
    return;
  }
  try {
    const user = await currentUser();
    if (!user) { window.location.replace('/login.html'); return; }
    if (user.role !== 'STUDENT') { window.location.replace('/home.html'); return; }
    const attempt = await apiJson(`/api/attempts/${attemptId}`);
    const exam = await apiJson(`/api/exams/${attempt.examId}`);
    $('#exam-title').textContent = exam.title;
    document.title = `${exam.title} · Examroom`;
    applyAttempt(attempt);
  } catch (error) {
    if (error.status === 401) window.location.replace('/login.html');
    else showGlobalError(error.message || 'The examination could not be loaded. Try refreshing.');
  }
}

init();
