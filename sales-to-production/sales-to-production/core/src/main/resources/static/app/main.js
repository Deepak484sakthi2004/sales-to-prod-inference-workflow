import { get, post, qs } from './api.js';
import { esc, toast, reasoning, freshnessChips } from './ui.js';
import * as sales from './views/sales.js';
import * as planning from './views/planning.js';
import * as pipeline from './views/pipeline.js';
import * as operations from './views/operations.js';
import * as review from './views/review.js';

const VIEWS = { sales, planning, pipeline, operations, review };
const HOME = { SALES_REP: 'sales', PLANNER: 'planning', SALES_HEAD: 'pipeline', OPS_HEAD: 'operations' };
const PEOPLE = { SALES_REP: 'Arjun (sales)', PLANNER: 'Priya Natarajan', SALES_HEAD: 'Lakshmi (sales head)', OPS_HEAD: 'A. Raman' };

function loadMe() {
  try { return JSON.parse(localStorage.getItem('plantdesk.me')) || { who: PEOPLE.PLANNER, role: 'PLANNER' }; }
  catch { return { who: PEOPLE.PLANNER, role: 'PLANNER' }; }
}
function saveMe() {
  try { localStorage.setItem('plantdesk.me', JSON.stringify(ctx.me)); } catch { /* storage unavailable: keep in memory */ }
}

const ctx = {
  me: loadMe(),
  catalog: null,
  refresh: () => route(),
  async catalogue(force) {
    if (!this.catalog || force) this.catalog = await get('/api/catalog');
    return this.catalog;
  },
};

const $ = id => document.getElementById(id);
let lastPromise = null;

async function header() {
  try {
    const [self, view] = await Promise.all([get('/api/self'), get('/api/views/planner')]);
    $('chips').innerHTML = freshnessChips(self.freshness);
    document.querySelectorAll('[data-count]').forEach(el => {
      const n = view.pending.filter(a => a.requiredRole === el.dataset.count).length;
      el.textContent = n;
      el.hidden = n === 0;
    });
  } catch (e) {
    $('chips').innerHTML = `<span class="chip UNAVAILABLE">core unreachable: ${esc(e.message)}</span>`;
  }
}

async function route() {
  const name = (location.hash || '').slice(1) || HOME[ctx.me.role] || 'planning';
  const view = VIEWS[name] || VIEWS.planning;
  document.querySelectorAll('#nav a').forEach(a => a.toggleAttribute('aria-current', a.dataset.route === name)
    && a.setAttribute('aria-current', 'page'));
  const root = $('view');
  try {
    await view.render(root, ctx);
  } catch (e) {
    root.innerHTML = `<section class="panel"><h2>Could not load this workspace</h2><p>${esc(e.message)}</p>
      <p class="muted">Check that the stand-ins and the core are running (./start.sh).</p></section>`;
  }
  header();
}

async function fillAskPickers() {
  try {
    const c = await ctx.catalogue();
    const f = $('askForm');
    f.accountId.insertAdjacentHTML('beforeend', c.accounts.map(a => `<option value="${esc(a.id)}">${esc(a.name)}</option>`).join(''));
    f.sku.insertAdjacentHTML('beforeend', c.products.map(p => `<option value="${esc(p.sku)}">${esc(p.sku)}</option>`).join(''));
  } catch { /* the view itself reports connection problems */ }
}

$('askForm').addEventListener('submit', async e => {
  e.preventDefault();
  const f = e.target;
  const context = {};
  if (f.accountId.value) { context.accountId = f.accountId.value; }
  if (f.sku.value) { context.sku = f.sku.value; }
  // A follow-up such as "promise the date anyway" refers to the last promise asked about.
  const carried = [];
  if (lastPromise && /\b(anyway|that date|the date|same)\b/i.test(f.q.value)) {
    for (const k of ['accountId', 'sku', 'qty', 'date']) {
      if (!context[k] && lastPromise[k]) { context[k] = lastPromise[k]; carried.push(k); }
    }
  }
  const box = $('answer');
  box.hidden = false;
  box.innerHTML = '<section class="panel"><p class="muted">Thinking with current data…</p></section>';
  try {
    const r = await post('/api/ask', { role: ctx.me.role, user: ctx.me.who, text: f.q.value, context });
    const rest = r.text.startsWith(r.headline) ? r.text.slice(r.headline.length).trim() : r.text;
    const ans = r.assessment.answer || {};
    if (ans.verdict && ans.sku) {
      // The model may have found the customer in the question itself; the server says what it actually used.
      const used = r.resolved || {};
      lastPromise = { accountId: used.accountId || context.accountId, sku: ans.sku, qty: String(ans.qty), date: ans.requested };
    }
    box.innerHTML = `<section class="panel">
      ${carried.length ? `<p class="muted small">Using your last promise question: ${esc(carried.map(k => k + ' ' + context[k]).join(', '))}</p>` : ''}
      <div class="title" style="display:flex;justify-content:space-between;gap:1rem"><p class="verdict">${esc(r.headline)}</p>
      <button id="closeAnswer" aria-label="Close answer">Close</button></div>
      ${rest ? `<pre class="text">${esc(rest)}</pre>` : ''}
      ${r.readAs ? `<p class="muted small">${esc(r.readAs)}</p>` : ''}
      ${reasoning(r.assessment)}</section>`;
    $('closeAnswer').onclick = () => { box.hidden = true; };
    header();
  } catch (err) {
    box.innerHTML = `<section class="panel"><p>${esc(err.message)}</p></section>`;
  }
});

// One delegated handler for the buttons every workspace shares.
document.addEventListener('click', async e => {
  const b = e.target.closest('button');
  if (!b) return;
  try {
    if (b.dataset.decide) {
      b.disabled = true;
      const approve = b.dataset.approve === 'true';
      const a = await post(`/api/actions/${b.dataset.decide}/decision`, { who: ctx.me.who, role: ctx.me.role, approve,
        note: approve ? 'approved in plant desk' : 'rejected in plant desk' });
      toast(`${a.id} ${a.status.toLowerCase()}${a.result ? ': ' + a.result : ''}`);
      ctx.refresh();
    } else if (b.dataset.accept) {
      b.disabled = true;
      const a = await post(`/api/suggestions/${b.dataset.accept}/accept`, { who: ctx.me.who, role: ctx.me.role, approve: true,
        note: 'accepted in plant desk' });
      toast(`${a.id} ${a.status.toLowerCase()}: ${a.result}`);
      ctx.refresh();
    } else if (b.dataset.explain) {
      const x = await get('/api/explain?' + qs({ key: b.dataset.explain }));
      const d = $('drawer');
      d.innerHTML = `<div style="display:flex;justify-content:space-between;gap:1rem;align-items:baseline">
        <h2>${esc(x.key)} = ${esc(x.value)}</h2><button id="closeDrawer">Close</button></div>
        <p>${esc(x.formula)}</p>
        ${x.inputs.length ? `<ul class="plain">${x.inputs.map(i => `<li>${esc(i.statement)} <span class="muted small">[${esc(i.source)} ${esc(i.ref)}, v${i.sourceVersion}]</span></li>`).join('')}</ul>` : '<p class="empty">No further inputs.</p>'}`;
      d.hidden = false;
      $('closeDrawer').onclick = () => { d.hidden = true; };
      $('closeDrawer').focus();
    }
  } catch (err) {
    toast(err.message);
    b.disabled = false;
  }
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') { $('drawer').hidden = true; } });

$('who').value = ctx.me.who;
$('role').value = ctx.me.role;
$('who').addEventListener('change', e => { ctx.me.who = e.target.value.trim() || PEOPLE[ctx.me.role]; saveMe(); });
$('role').addEventListener('change', e => {
  ctx.me.role = e.target.value;
  if (Object.values(PEOPLE).includes($('who').value)) { ctx.me.who = PEOPLE[ctx.me.role]; $('who').value = ctx.me.who; }
  saveMe();
  route();
});
window.addEventListener('hashchange', route);
fillAskPickers();
route();
setInterval(header, 30000);
