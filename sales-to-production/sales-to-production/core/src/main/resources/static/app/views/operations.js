import { get, post, qs } from '../api.js';
import { esc, time, date, words, freshnessTable, actionCard, reasoning, toast, pill } from '../ui.js';

let lastTrace = null;
let logFilter = '';

// Operations: the system's own state, the rules in force, traces of what was said, and every decision.
export async function render(root, ctx) {
  const [self, policy, view, decisions, cat] = await Promise.all([get('/api/self'), get('/api/policy'),
    get('/api/views/planner'), get('/api/decisions?n=80'), ctx.catalogue()]);
  const s = self.answer;
  const kinds = [...new Set(decisions.map(d => d.kind))].sort();
  const shown = decisions.filter(d => !logFilter || d.kind === logFilter);
  root.innerHTML = `
  <div class="pagehead"><h1>Operations</h1><span class="muted">${esc(self.headline)}</span></div>
  <div class="cols even">
    <div>
      <section class="panel" aria-labelledby="trace-h">
        <h2 id="trace-h">Why did the system say that?</h2>
        <form class="row" id="traceForm">
          <label style="flex:1 1 18rem">Question<input name="q" value="Why did the system tell the customer the 18th last week?"></label>
          <label>Customer<select name="accountId"><option value="">any</option>${cat.accounts.map(a => `<option value="${esc(a.id)}"${a.id === 'ACC-KAV' ? ' selected' : ''}>${esc(a.name)}</option>`).join('')}</select></label>
          <button class="primary" type="submit">Trace</button>
        </form>
        <div id="traceOut">${lastTrace ? trace(lastTrace) : ''}</div>
      </section>
      <section class="panel" aria-labelledby="pol-h">
        <h2 id="pol-h">Rules in force</h2>
        <p class="small muted">Policy ${esc(policy.version)}. Changes take effect on the next question and are written to the decision log under your name.</p>
        <div class="row" style="align-items:center;margin-bottom:.8rem">
          <span>Minimum buffer on every promise:</span>
          <button id="slackDown" aria-label="One working day less">−</button>
          <strong id="slack">${policy.minSlackWorkingDays} working day(s)</strong>
          <button id="slackUp" aria-label="One working day more">+</button>
        </div>
        <table class="small"><thead><tr><th>Rule</th><th>What it lets the system do without waiting</th><th>Approved by</th><th></th></tr></thead><tbody>
        ${policy.rules.map(r => `<tr><td>${esc(r.id)} ${r.enabled ? pill('on', 'good') : pill('off', 'info')}</td><td>${esc(r.description)}</td>
          <td class="small">${esc(r.approvedBy)}, ${date(r.approvedOn)}</td>
          <td><button data-rule="${esc(r.id)}" data-enabled="${!r.enabled}">${r.enabled ? 'Turn off' : 'Turn on'}</button></td></tr>`).join('')}</tbody></table>
        <p class="small muted">Goodwill lost per late day by tier: ${Object.entries(policy.goodwillPctPerLateDay).map(([k, v]) => `${k} ${v}%`).join(', ')}.
          Planning data counts as stale after ${policy.planningSlaMinutes} min; nothing is committed on data older than ${policy.commitLimitMinutes} min.</p>
      </section>
      <section class="panel" aria-labelledby="pend-h">
        <h2 id="pend-h">Everything waiting for a person</h2>
        ${view.pending.length ? view.pending.map(a => actionCard(a, ctx.me)).join('') : '<p class="empty">Nothing waits.</p>'}
      </section>
    </div>
    <div>
      <section class="panel" aria-labelledby="self-h">
        <h2 id="self-h">What the system knows about itself</h2>
        ${freshnessTable(s.freshness)}
        <h3>Pending work</h3>
        <p>${s.pendingActions} action(s) waiting${s.oldestPending ? `, oldest ${esc(s.oldestPending)}` : ''}; ${s.unprocessedMail} unread mail.</p>
        <h3>Forecast accuracy</h3>
        <ul class="plain">${s.lineHistory.map(l => `<li>${esc(l)}</li>`).join('')}<li>${esc(s.promiseAccuracy)}</li><li>${esc(s.quoteModel)}</li></ul>
        <h3>Do not trust yet</h3>
        <ul class="plain">${s.doNotTrustYet.map(l => `<li>${esc(l)}</li>`).join('')}</ul>
      </section>
      <section class="panel" aria-labelledby="log-h">
        <div class="title" style="display:flex;justify-content:space-between;gap:1rem;align-items:baseline">
          <h2 id="log-h">Decision log</h2>
          <select id="logFilter" aria-label="Filter by kind"><option value="">all kinds</option>${kinds.map(k => `<option value="${esc(k)}"${k === logFilter ? ' selected' : ''}>${words(k)}</option>`).join('')}</select></div>
        <table class="small"><tbody>${shown.map(d => `<tr><td class="nw muted">${time(d.at)}</td>
          <td><strong>${words(d.kind)}</strong> ${esc(d.statement)}${d.approvedBy ? `<div class="muted">by ${esc(d.approvedBy)}</div>` : ''}
          ${d.basis && d.basis.length ? `<details><summary>${d.basis.length} basis fact(s), data versions</summary><ul class="plain">${d.basis.map(b => `<li>${esc(b)}</li>`).join('')}</ul>
            <p class="muted">${Object.entries(d.sourceVersions || {}).map(([k, v]) => `${k} v${v} (${esc((d.dataAge || {})[k] || '?')})`).join(', ')}</p></details>` : ''}</td>
          <td class="nw muted">${esc(d.id)}</td></tr>`).join('')}</tbody></table>
      </section>
    </div>
  </div>`;

  root.querySelector('#traceForm').onsubmit = async e => {
    e.preventDefault();
    try { lastTrace = await get('/api/trace?' + qs({ q: e.target.q.value, accountId: e.target.accountId.value })); }
    catch (err) { toast(err.message); return; }
    root.querySelector('#traceOut').innerHTML = trace(lastTrace);
  };
  const setSlack = async days => {
    if (days < 0) return;
    try { await post('/api/policy/min-slack?' + qs({ days, who: ctx.me.who })); toast(`Minimum buffer is now ${days} working day(s)`); }
    catch (err) { toast(err.message); }
    ctx.refresh();
  };
  root.querySelector('#slackDown').onclick = () => setSlack(policy.minSlackWorkingDays - 1);
  root.querySelector('#slackUp').onclick = () => setSlack(policy.minSlackWorkingDays + 1);
  root.querySelectorAll('[data-rule]').forEach(b => b.onclick = async () => {
    try { await post(`/api/policy/rules/${b.dataset.rule}?` + qs({ enabled: b.dataset.enabled, who: ctx.me.who })); toast(`${b.dataset.rule} turned ${b.dataset.enabled === 'true' ? 'on' : 'off'}`); }
    catch (err) { toast(err.message); }
    ctx.refresh();
  });
  root.querySelector('#logFilter').onchange = e => { logFilter = e.target.value; ctx.refresh(); };
}

function trace(a) {
  const t = a.answer;
  if (!t) return `<p>${esc(a.headline)}</p>`;
  const d = t.decision;
  return `<div style="margin-top:.8rem"><p class="verdict">${esc(a.headline)}</p>
    <div class="card"><div class="title"><strong>${esc(d.id)} ${words(d.kind)}</strong><span class="muted small">${time(d.at)}</span></div>
      <p>${esc(d.statement)}</p><p class="small muted">Approved by ${esc(d.approvedBy || 'n/a')}${d.ruleId ? ' under rule ' + esc(d.ruleId) : ''}</p></div>
    <h3>Data it used at the time</h3><ul class="plain">${t.then.map(x => `<li>${esc(x)}</li>`).join('')}</ul>
    <h3>What changed since</h3><ul class="plain">${t.since.map(x => `<li>${esc(x)}</li>`).join('')}</ul>
    <h3>Now</h3><p>${esc(t.now)}</p>
    ${t.laterDecisions.length ? `<h3>Said to the customer since</h3><ul class="plain">${t.laterDecisions.map(l => `<li>${time(l.at)}: ${esc(l.statement)}</li>`).join('')}</ul>` : ''}
    <details><summary>Confidence and reasoning</summary>${reasoning(a)}</details></div>`;
}
