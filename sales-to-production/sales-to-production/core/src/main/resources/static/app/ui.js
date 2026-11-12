// Shared rendering. Every server answer is an Assessment; these helpers show it the same way everywhere.

export const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const inr = n => n === null || n === undefined ? 'n/a'
  : (Number(n) < 0 ? '−₹' : '₹') + Math.abs(Number(n)).toLocaleString('en-IN', { maximumFractionDigits: 0 });
export const date = d => d ? new Date(d + 'T00:00:00Z').toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }) : 'n/a';
export const time = t => t ? new Date(t).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '';
export const age = m => m === null || m === undefined || m < 0 ? 'no data' : m < 1 ? 'under a minute' : m < 120 ? m + ' min' : m < 2880 ? Math.floor(m / 60) + ' h' : Math.floor(m / 1440) + ' days';
export const words = s => String(s ?? '').replaceAll('_', ' ').toLowerCase();

export function pill(text, tone) {
  return `<span class="pill ${tone}">${esc(text)}</span>`;
}

export function statePill(state) {
  const tone = { LATE: 'bad', AT_RISK: 'warn', ON_TRACK: 'good', UNPLANNED: 'info' }[state] || 'info';
  return pill(words(state), tone);
}

export function outcomePill(outcome) {
  const tone = { QUOTATION_DRAFTED: 'good', ORDER_RECORDED: 'good', CLARIFICATION_DRAFTED: 'warn', DUPLICATE: 'info',
    STATUS_REPLY_DRAFTED: 'info', UNKNOWN_SENDER: 'bad', NOT_ACTIONABLE: 'bad' }[outcome] || 'info';
  return pill(words(outcome), tone);
}

export function actionPill(status) {
  const tone = { PENDING: 'warn', EXECUTED: 'good', REJECTED: 'info', FAILED: 'bad', INVALIDATED: 'bad' }[status] || 'info';
  return pill(words(status), tone);
}

export function freshnessChips(list) {
  return list.map(f => `<span class="chip ${f.status}" title="${esc(f.note || '')}">${f.source.replace('_', ' ')} ${
    f.status === 'FRESH' ? age(f.ageMinutes) + ' old' : words(f.status)}</span>`).join('');
}

/** Confidence drawn as a ledger: start at 1.00, each named factor shrinks the bar by its multiplier. */
export function ledger(conf) {
  let level = 1;
  const steps = conf.factors.map(f => {
    const before = level;
    level = level * f.multiplier;
    return `<div class="step" title="${esc(f.reason)}">
      <span>${esc(f.name)}</span><span class="num">×${f.multiplier.toFixed(2)}</span>
      <span class="track"><i class="lost" style="left:${(level * 100).toFixed(1)}%;width:${((before - level) * 100).toFixed(1)}%"></i>
        <i class="kept" style="width:${(level * 100).toFixed(1)}%"></i><span>${level.toFixed(2)}</span></span>
      <span class="why">${esc(f.reason)}</span></div>`;
  }).join('');
  return `<div class="ledger" role="img" aria-label="Confidence ${conf.score} ${conf.level}">
    ${steps}
    <div class="step total"><strong>Confidence</strong><span class="num"><strong>${conf.score.toFixed(2)}</strong></span>
      <span class="track"><i class="kept" style="width:${(conf.score * 100).toFixed(1)}%"></i><span>${words(conf.level)}</span></span></div>
  </div>`;
}

export function freshnessTable(list) {
  return `<table class="small"><thead><tr><th>Source</th><th>Age</th><th>SLA</th><th>Status</th><th>Note</th></tr></thead><tbody>${
    list.map(f => `<tr><td>${f.source}</td><td class="nw">${f.asOf ? age(f.ageMinutes) : f.status === 'FRESH' ? 'on demand' : 'no data'}</td><td class="nw">${age(f.slaMinutes)}</td>
      <td>${pill(words(f.status), f.status === 'FRESH' ? 'good' : f.status === 'STALE' ? 'warn' : 'bad')}</td><td class="muted">${esc(f.note || '')}</td></tr>`).join('')
  }</tbody></table>`;
}

/** The reasoning half of any assessment: confidence, freshness, basis, what was not checked, what would change it. */
export function reasoning(a, { open = false } = {}) {
  const list = items => items.length ? `<ul class="plain">${items.map(i => `<li>${esc(i)}</li>`).join('')}</ul>` : '<p class="empty">None.</p>';
  return `
    <h3>How sure, and why</h3>${ledger(a.confidence)}
    <details ${open ? 'open' : ''}><summary>Data used, its age, and what would change the answer</summary>
      <h3>Freshness</h3>${freshnessTable(a.freshness)}
      <h3>Basis</h3>${a.basis.length ? `<ul class="plain">${a.basis.map(b => `<li>${esc(b.statement)} <span class="muted small">[${esc(b.source)} ${esc(b.ref)}, v${b.sourceVersion}, ${age(b.ageMinutes)} old]</span></li>`).join('')}</ul>` : '<p class="empty">No source facts.</p>'}
      <h3>Not checked</h3>${list(a.notChecked)}
      <h3>What would change the answer</h3>${list(a.wouldChange)}
      ${a.decisionId ? `<p class="muted small">Decision record ${esc(a.decisionId)}</p>` : ''}
    </details>`;
}

function draftOf(a) {
  const p = a.payload || {};
  if (p.mail) return p.mail;
  if (p.notifyMail) return p.notifyMail;
  return null;
}

export function actionCard(a, me) {
  const draft = draftOf(a);
  const status = a.payload && a.payload.status;
  const canDecide = a.status === 'PENDING' && (me.role === a.requiredRole || me.role === 'OPS_HEAD');
  return `<div class="card">
    <div class="title"><strong>${esc(a.id)} <span class="muted" style="font-weight:400">${words(a.type)}</span></strong>${actionPill(a.status)}</div>
    <div>${esc(a.summary)}</div>
    <div class="muted small">Needs ${esc(a.requiredRole)}${a.decidedBy ? `; decided by ${esc(a.decidedBy)}` : ''}${a.result ? `: ${esc(a.result)}` : ''}</div>
    ${draft ? `<details><summary>Draft to customer: ${esc(draft.subject)}</summary><pre class="text">${esc(draft.body)}</pre></details>` : ''}
    ${status ? `<details><summary>What the customer will see</summary><pre class="text">${esc(words(status.stage))}; ${status.currentEstimate ? 'estimate ' + date(status.currentEstimate) : 'date being reconfirmed'}\n${esc(status.message)}</pre></details>` : ''}
    ${a.status === 'PENDING' ? (canDecide
      ? `<div class="actions"><button class="primary" data-decide="${esc(a.id)}" data-approve="true">Approve</button><button data-decide="${esc(a.id)}" data-approve="false">Reject</button></div>`
      : `<p class="muted small">Switch to ${esc(a.requiredRole)} to decide this.</p>`) : ''}
  </div>`;
}

export function suggestionCard(s, me) {
  const canAccept = s.payload && (me.role === s.requiredRole || me.role === 'OPS_HEAD');
  return `<div class="card">
    <div class="title"><strong>${esc(s.id)}</strong><span class="muted small">decided by ${esc(s.requiredRole)}, confidence ${s.confidence.toFixed(2)}</span></div>
    <dl><dt>Protects</dt><dd>${esc(s.outcome)}</dd><dt>Action</dt><dd>${esc(s.action)}</dd>
      <dt>Cost</dt><dd>${inr(s.cost)} (${esc(s.costNote)})</dd>
      <dt>If nobody acts</dt><dd>${esc(s.riskIfNobodyActs)}; expected loss ${inr(s.expectedLossIfNobodyActs)}</dd>
      <dt>Versus nothing</dt><dd class="net">${inr(s.netBenefit)} better (expected loss ${inr(s.expectedLossIfActed)} incl. cost)</dd></dl>
    <details><summary>What would change this</summary><ul class="plain">${s.wouldChange.map(w => `<li>${esc(w)}</li>`).join('')}</ul></details>
    ${s.payload ? (canAccept ? `<div class="actions"><button class="primary" data-accept="${esc(s.id)}">Accept and run</button></div>`
      : `<p class="muted small">Switch to ${esc(s.requiredRole)} to accept this.</p>`) : `<p class="muted small">Advice for ${esc(s.requiredRole)}; nothing for the system to execute.</p>`}
  </div>`;
}

export function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 5000);
}

export function busy(button, on) {
  if (!button) return;
  button.disabled = on;
}
