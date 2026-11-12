// Thin client for the core's REST API. Every error carries the server's message.
export async function api(path, opts = {}) {
  const r = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts });
  const type = r.headers.get('content-type') || '';
  const body = type.includes('json') ? await r.json().catch(() => ({})) : await r.text();
  if (!r.ok) throw new Error((body && body.error) || r.statusText || 'Request failed');
  return body;
}
export const get = path => api(path);
export const post = (path, body) => api(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
export const qs = params => Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')
  .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
