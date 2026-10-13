import { currentUser, signOut } from './auth.js';

const content = document.querySelector('#account-content');
const loading = document.querySelector('#account-loading');
const error = document.querySelector('#account-error');
const signOutButton = document.querySelector('#sign-out');

function showError(message) {
  error.textContent = message;
  error.hidden = false;
}

async function load() {
  try {
    const user = await currentUser();
    if (!user) {
      window.location.replace('/login.html');
      return;
    }
    document.querySelector('#display-name').textContent = user.displayName;
    document.querySelector('#account-username').textContent = user.username;
    document.querySelector('#account-role').textContent = user.role === 'INSTRUCTOR' ? 'Instructor' : 'Student';
    document.querySelector('#role-message').textContent = user.role === 'INSTRUCTOR'
      ? 'Create and publish examinations, manage questions, and review student results.'
      : 'Browse published examinations, resume your attempts, and view completed results.';
    document.querySelector('#instructor-link').hidden = user.role !== 'INSTRUCTOR';
    document.querySelector('#student-link').hidden = user.role !== 'STUDENT';
    document.querySelector('#home-instructor-nav').hidden = user.role !== 'INSTRUCTOR';
    document.querySelector('#home-student-nav').hidden = user.role !== 'STUDENT';
    loading.hidden = true;
    content.hidden = false;
    signOutButton.disabled = false;
  } catch {
    loading.hidden = true;
    showError('Your account could not be loaded. Refresh the page to try again.');
  }
}

signOutButton.addEventListener('click', async () => {
  error.hidden = true;
  signOutButton.disabled = true;
  try {
    await signOut();
    window.location.replace('/login.html');
  } catch {
    signOutButton.disabled = false;
    showError('Sign out could not be completed. Please try again.');
  }
});

load();
