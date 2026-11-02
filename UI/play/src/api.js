const base = process.env.REACT_APP_API_URL || '';

export async function api(path, options = {}, token) {
  const { timeout = 15000, ...requestOptions } = options;
  let response;
  try {
    response = await fetch(`${base}${path}`, {
      ...requestOptions,
      signal: options.signal || (timeout == null ? undefined : AbortSignal.timeout(timeout)),
      headers: {
        ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...options.headers,
      },
    });
  } catch (cause) {
    if (cause.name === 'TimeoutError' || (cause.name === 'AbortError' && !options.signal)) {
      const error = new Error('This request took too long. Please try again.');
      error.status = 408;
      throw error;
    }
    throw cause;
  }
  if (!response.ok) {
    const message = response.status === 400 || response.status === 422 ? 'Please review the entered information and try again.'
      : response.status === 401 ? 'Please sign in to continue.'
      : response.status === 403 ? 'You do not have permission to do that.'
      : response.status === 404 ? 'The requested item is no longer available.'
      : response.status === 409 ? 'This item was just reserved by another shopper. Refresh the marketplace and choose another item.'
      : response.status === 410 ? 'Your payment session expired. Please try checkout again.'
      : 'This service is still starting or temporarily unavailable. Please wait while it reconnects.';
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) return null;
  if (options.responseType === 'blob') return response.blob();
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

export async function recordActivity(action, token) {
  try { await api('/api/auth/activities', { method: 'POST', body: JSON.stringify({ action }) }, token); } catch { /* Activity history must not interrupt the completed action. */ }
}
