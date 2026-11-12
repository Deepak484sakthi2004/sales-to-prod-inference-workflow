import { get, post, qs } from '../api.js';
import { esc, date, outcomePill, toast } from '../ui.js';

const log = [];

// Review console: inject the review set's enquiries and production events, then let the system react.
export async function render(root, ctx) {
  const cat = await ctx.catalogue(true);
  const tomorrow = addDays(cat.today, 1);
  root.innerHTML = `
  <div class="pagehead"><h1>Review console</h1><span class="muted">Feed new situations into the stand-in systems, then watch every workspace react.</span></div>
  <div class="cols even">
    <div>
      <section class="panel" aria-labelledby="mail-h">
        <h2 id="mail-h">A customer email arrives</h2>
        <form id="mailForm" style="display:grid;gap:.5rem">
          <div class="row">
            <label style="flex:1">From<input name="from" list="contacts" value="${esc(cat.accounts[0]?.contactEmail || '')}"></label>
            <label style="flex:1">Subject<input name="subject" value="Requirement"></label>
          </div>
          <datalist id="contacts">${cat.accounts.map(a => `<option value="${esc(a.contactEmail)}">${esc(a.name)}</option>`).join('')}</datalist>
          <label class="small muted">Body<textarea name="body" rows="4">We need 120 units of the 45mm flexible coupling by the 24th. Please confirm.</textarea></label>
          <details><summary>Add a CSV attachment</summary><textarea name="csv" rows="3" placeholder="item,qty,required_by&#10;Weld neck flange 150mm,40,27th"></textarea></details>
          <div class="actions"><button class="primary" type="submit">Deliver and read it</button></div>
        </form>
      </section>
      <section class="panel" aria-labelledby="prod-h">
        <h2 id="prod-h">Production moves</h2>
        <form class="row" id="slipForm">
          <label>Line<select name="lineId">${cat.lines.map(l => `<option${l === 'L2' ? ' selected' : ''}>${esc(l)}</option>`).join('')}</select></label>
          <label>Working days lost<input name="workingDays" type="number" min="1" max="10" value="2" style="width:5rem"></label>
          <label>From<input name="from" type="date" value="${tomorrow}"></label>
          <label style="flex:1">Reason<input name="reason" value="gearbox failure on main spindle"></label>
          <button class="primary" type="submit">Record the slip</button>
        </form>
        <h3>Work order progress</h3>
        <form class="row" id="progressForm">
          <label>Work order<select name="workOrderId">${cat.workOrders.map(w => `<option value="${esc(w.id)}">${esc(w.id)} ${esc(w.orderId)} ${esc(w.lineId)} (${w.completedQty}/${w.qty})</option>`).join('')}</select></label>
          <label>Completed so far<input name="completedQty" type="number" min="0" value="50" style="width:6rem"></label>
          <button type="submit">Report progress</button>
        </form>
      </section>
      <section class="panel" aria-labelledby="data-h">
        <h2 id="data-h">Data goes stale</h2>
        <form class="row" id="lagForm">
          <label>Planning feed behind by (minutes)<input name="minutes" type="number" min="0" value="180" style="width:7rem"></label>
          <button type="submit">Apply</button><button type="button" id="lagClear">Back to live</button>
        </form>
      </section>
    </div>
    <div>
      <section class="panel" aria-labelledby="cycle-h">
        <h2 id="cycle-h">Let the system react</h2>
        <p class="small muted">A sync cycle reads new mail, re-plans if production changed, and brings customer status in line. It also runs every 30 seconds on its own.</p>
        <div class="actions"><button class="primary" id="tick">Run a sync cycle</button>
          <button id="suite">Run all scenarios</button></div>
        <p class="small muted">Run all scenarios resets every stand-in to its seed before and after, and returns the run log as Markdown.</p>
      </section>
      <section class="panel" aria-labelledby="log-h">
        <h2 id="log-h">What happened</h2>
        <div id="log">${log.length ? log.join('') : '<p class="empty">Nothing yet.</p>'}</div>
      </section>
    </div>
  </div>`;

  const record = (title, body) => {
    log.unshift(`<div class="card"><div class="title"><strong>${esc(title)}</strong><span class="muted small">${new Date().toLocaleTimeString('en-IN')}</span></div>${body}</div>`);
    root.querySelector('#log').innerHTML = log.join('');
  };
  const guard = fn => async e => {
    e.preventDefault();
    const b = e.submitter || e.target;
    if (b) b.disabled = true;
    try { await fn(e); } catch (err) { toast(err.message); record('Failed', `<p>${esc(err.message)}</p>`); }
    if (b) b.disabled = false;
  };

  root.querySelector('#mailForm').onsubmit = guard(async e => {
    const f = e.target;
    const attachments = f.csv.value.trim() ? [{ name: 'rfq.csv', contentType: 'text/csv', text: f.csv.value.trim() }] : [];
    const m = await post('/api/sim/mail', { from: f.from.value, subject: f.subject.value, body: f.body.value, attachments });
    const read = await post('/api/mailbox/poll');
    record(`Mail ${m.id} delivered and read`, read.map(a => `<p>${outcomePill(a.answer.outcome)} ${esc(a.headline)}</p>`).join('') || '<p class="empty">Nothing new was read.</p>');
    toast('Mail read: see Sales desk for the draft');
  });
  root.querySelector('#slipForm').onsubmit = guard(async e => {
    const f = e.target;
    const ev = await post('/api/sim/slip', { lineId: f.lineId.value, workingDays: Number(f.workingDays.value), from: f.from.value, reason: f.reason.value });
    record(`Planning event #${ev.seq}`, `<p>${esc(ev.detail)}</p><p class="small muted">Ask the planner question, or run a sync cycle, to see the re-plan.</p>`);
  });
  root.querySelector('#progressForm').onsubmit = guard(async e => {
    const f = e.target;
    const ev = await post('/api/sim/progress?' + qs({ workOrderId: f.workOrderId.value, completedQty: f.completedQty.value }));
    record(`Planning event #${ev.seq}`, `<p>${esc(ev.workOrderId)}: ${esc(ev.detail)}</p>`);
  });
  root.querySelector('#lagForm').onsubmit = guard(async e => {
    await post('/api/sim/feed-lag?' + qs({ minutes: e.target.minutes.value }));
    record('Planning feed delayed', `<p>Planning now reports data ${esc(e.target.minutes.value)} minutes old. Answers that need it become indicative only once it passes the commit limit.</p>`);
  });
  root.querySelector('#lagClear').onclick = guard(async () => {
    await post('/api/sim/feed-lag?minutes=0');
    record('Planning feed live again', '<p>Lag cleared.</p>');
  });
  root.querySelector('#tick').onclick = guard(async () => {
    const notes = await post('/api/tick');
    record('Sync cycle', notes.length ? `<ul class="plain">${notes.map(n => `<li>${esc(n)}</li>`).join('')}</ul>` : '<p class="empty">Nothing changed.</p>');
  });
  root.querySelector('#suite').onclick = guard(async () => {
    if (!confirm('This resets every stand-in to its seed data before and after the run. Continue?')) return;
    const log = await fetch('/api/scenarios/run', { method: 'POST' }).then(r => r.ok ? r.text() : Promise.reject(new Error('Scenario run failed')));
    const url = URL.createObjectURL(new Blob([log], { type: 'text/plain' }));
    record('Scenario suite finished', `<p>${(log.match(/^S\d\d\. /gm) || []).length} scenarios. <a href="${url}" download="RUN_LOG.live.txt">Download the run log</a></p>`);
  });
}

function addDays(iso, n) {
  const d = new Date(iso + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  if (d.getUTCDay() === 0) d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}
export { date };
