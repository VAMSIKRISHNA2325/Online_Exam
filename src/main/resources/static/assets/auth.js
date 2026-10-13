const REQUEST_TIMEOUT_MS = 15000;

async function request(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(path, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      signal: controller.signal
    });
    const contentType = response.headers.get('content-type') || '';
    const data = contentType.includes('application/json') ? await response.json() : null;
    return { response, data };
  } finally {
    clearTimeout(timer);
  }
}

async function csrf() {
  const { response, data } = await request('/api/csrf');
  if (!response.ok || !data?.headerName || !data?.token) {
    throw new Error('Request protection is unavailable. Please try again.');
  }
  return data;
}

export async function currentUser() {
  const { response, data } = await request('/api/me');
  if (response.status === 401) return null;
  if (!response.ok) throw new Error('Your account could not be loaded. Please try again.');
  return data;
}

export async function signIn(username, password) {
  const token = await csrf();
  const body = new URLSearchParams({ username, password });
  const { response, data } = await request('/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [token.headerName]: token.token },
    body
  });
  if (response.ok) return { ok: true };
  return { ok: false, status: response.status, code: data?.code };
}

export async function signOut() {
  const token = await csrf();
  const { response } = await request('/logout', {
    method: 'POST',
    headers: { [token.headerName]: token.token }
  });
  if (!response.ok) throw new Error('Sign out could not be completed. Please try again.');
}

export async function apiJson(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && method !== 'HEAD') {
    const token = await csrf();
    headers[token.headerName] = token.token;
  }
  const { response, data } = await request(path, {
    method,
    headers,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {})
  });
  if (!response.ok) {
    const error = new Error(data?.message || 'The request could not be completed. Please try again.');
    error.status = response.status;
    error.code = data?.code;
    error.current = data?.current;
    throw error;
  }
  return data;
}
