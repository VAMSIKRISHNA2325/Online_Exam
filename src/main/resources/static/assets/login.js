import { currentUser, signIn } from './auth.js';

const form = document.querySelector('#login-form');
const username = document.querySelector('#username');
const password = document.querySelector('#password');
const submit = document.querySelector('#sign-in');
const error = document.querySelector('#login-error');
const toggle = document.querySelector('#toggle-password');

function showError(message) {
  error.textContent = message;
  error.hidden = false;
}

function clearError() {
  error.hidden = true;
  error.textContent = '';
}

toggle.addEventListener('click', () => {
  const visible = password.type === 'text';
  password.type = visible ? 'password' : 'text';
  toggle.textContent = visible ? 'Show' : 'Hide';
  toggle.setAttribute('aria-label', visible ? 'Show password' : 'Hide password');
  toggle.setAttribute('aria-pressed', String(!visible));
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  clearError();
  if (!username.value.trim() || !password.value) {
    showError('Enter your username and password.');
    (!username.value.trim() ? username : password).focus();
    return;
  }
  submit.disabled = true;
  submit.querySelector('span').textContent = 'Signing in…';
  try {
    const result = await signIn(username.value.trim(), password.value);
    if (result.ok) {
      window.location.replace('/home.html');
      return;
    }
    password.value = '';
    if (result.code === 'SESSION_IN_USE') {
      showError('This student account is already signed in. Sign out on the other device, or contact your exam coordinator if you cannot access it.');
    } else if (result.code === 'LOGIN_THROTTLED') {
      showError('Sign in is temporarily paused after several unsuccessful attempts. Wait a moment and try again.');
    } else if (result.code === 'BAD_CREDENTIALS') {
      showError('Username or password is incorrect. Please try again.');
    } else if (result.status === 503) {
      showError('Sign in is temporarily unavailable. Please try again shortly.');
    } else {
      showError('Sign in could not be completed. Please try again.');
    }
    password.focus();
  } catch {
    showError('We could not reach Examroom. Check your connection and try again.');
  } finally {
    submit.disabled = false;
    submit.querySelector('span').textContent = 'Sign in';
  }
});

currentUser().then(user => {
  if (user) window.location.replace('/home.html');
}).catch(() => {
  // The form remains usable if the initial session lookup fails.
});
