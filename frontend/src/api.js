// Thin fetch wrapper. Every API error becomes an Error with the server's message.
// On 401 it calls the registered handler (App shows the login overlay) and throws UnauthorizedError,
// so callers can keep their local state (drafts are never discarded on 401).

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export class UnauthorizedError extends ApiError {
  constructor(message) {
    super(message, 401);
  }
}

let unauthorizedHandler = () => {};

export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

const OFFLINE_MESSAGE = "Can't reach the FitLocal server. Nothing was lost; try again when you're back online.";

export async function api(path, { method = 'GET', body } = {}) {
  const hasBody = body !== undefined;
  let response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: hasBody ? { 'Content-Type': 'application/json' } : {},
      body: hasBody ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(OFFLINE_MESSAGE, 0);
  }
  if (response.status === 204) return null;
  let data = null;
  try {
    data = await response.json();
  } catch {
    // Non-JSON body (e.g. a proxy error page): fall through to the generic message.
  }
  if (response.status === 401 && path !== '/api/auth/login') {
    unauthorizedHandler();
    throw new UnauthorizedError('Your session has ended. Sign in again, then retry.');
  }
  if (!response.ok) {
    throw new ApiError(data?.error || `Request failed (HTTP ${response.status})`, response.status);
  }
  return data;
}
