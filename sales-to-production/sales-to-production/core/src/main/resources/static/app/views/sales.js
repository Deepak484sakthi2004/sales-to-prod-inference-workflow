import { get, post, qs } from '../api.js';
import { esc, inr, date, words, outcomePill, actionCard, reasoning, toast, pill } from '../ui.js';

// Sales desk: check a promise, read customer mail, approve what goes to customers.
export async function render(root, ctx) {
  const [cat, enquiries, view, actions] = await Promise.all([ctx.catalogue(), get('/api/enquiries'),
    get('/api/views/planner'), get('/api/actions')]);
  const status = Object.fromEntries(actions.map(a => [a.id, a]));
  const mine = view.pending.filter(a => a.requiredRole === 'SALES_REP');
  const the20th = nextDay(cat.today, 20);
  root.innerHTML = `
  <div class="pagehead"><h1>Sales desk</h1>
    <div class="tools"><button id="readMail" class="primary">Read new mail</button></div></div>
  <div class="cols">
    <div>
      <section class="panel" aria-labelledby="promise-h">
        <h2 id="promise-h">Check a promise before you give it</h2>
        <form class="row" id="promiseForm">
          <label>Customer<select name="accountId">${cat.accounts.map(a => `<option value="${esc(a.id)}"${a.id === 'ACC-SUND' ? ' selected' : ''}>${esc(a.name)}</option>`).join('')}</select></label>
          <label>Product<select name="sku">${cat.products.map(p => `<option value="${esc(p.sku)}"${p.sku === 'CPL-60-FX' ? ' selected' : ''}>${esc(p.sku)} ${esc(p.name)}</option>`).join('')}</select></label>
          <label>Quantity<input name="qty" type="number" min="1" value="250" style="width:6rem"></label>
          <label>Needed by<input name="date" type="date" value="${the20th}"></label>
          <button class="primary" type="submit">Check</button>
        </form>
        <div id="promiseOut"></div>
      </section>
      <section class="panel" aria-labelledby="mail-h">
        <h2 id="mail-h">Customer mail, as the system read it</h2>
        ${enquiries.length ? enquiries.map(a => enquiry(a, status)).join('') : '<p class="empty">No mail read yet. Use Read new mail.</p>'}
      </section>
    </div>
    <div>
      <section class="panel" aria-labelledby="mine-h">
        <h2 id="mine-h">Waiting for a sales decision</h2>
        ${mine.length ? mine.map(a => actionCard(a, ctx.me)).join('') : '<p class="empty">Nothing waits for sales right now.</p>'}
      </section>
    </div>
  </div>`;

  root.querySelector('#readMail').onclick = async e => {
    e.target.disabled = true;
    const r = await post('/api/mailbox/poll');
    toast(r.length ? `Read ${r.length} new message(s)` : 'No new mail');
    ctx.refresh();
  };
  root.querySelector('#promiseForm').onsubmit = async e => {
    e.preventDefault();
    const f = e.target;
    const out = root.querySelector('#promiseOut');
    out.innerHTML = '<p class="muted">Checking stock, capacity and material…</p>';
    try {
      const a = await get('/api/promise?' + qs({ sku: f.sku.value, qty: f.qty.value, date: f.date.value, accountId: f.accountId.value }));
      out.innerHTML = promiseResult(a);
    } catch (err) { out.innerHTML = `<p>${esc(err.message)}</p>`; }
  };
}

function promiseResult(a) {
  const p = a.answer;
  const d = p.detail;
  const tone = p.verdict === 'YES' || p.verdict === 'EARLIEST' ? 'yes' : p.verdict === 'NO' ? 'no' : 'maybe';
  const byLine = {};
  (d.placements || []).forEach(pl => { (byLine[pl.lineId] ||= []).push(pl); });
  return `<div style="margin-top:1rem">
    <p class="verdict ${tone}">${esc(p.verdict === 'YES' ? `Yes: ${p.qty} by ${date(p.requested)}`
      : p.verdict === 'NO' ? `No: ${p.qtyByRequested} by ${date(p.requested)}, all ${p.qty} by ${date(p.supportedDate)}`
      : p.verdict === 'EARLIEST' ? `Earliest: ${date(p.supportedDate)}` : 'Indicative only: data too old to commit')}</p>
    <p>${esc(p.statement)}</p>
    <table class="small"><thead><tr><th>Source of the units</th><th class="num">Units</th><th>When</th></tr></thead><tbody>
      <tr><td>Unreserved stock</td><td class="num">${d.fromStock}</td><td>${d.toMake === 0 ? 'ships ' + date(d.dispatchDate) : 'ships with the made units'}</td></tr>
      ${Object.entries(byLine).map(([line, pls]) => `<tr><td>Made on ${esc(line)}${pls[0].alternate ? ' (alternate line)' : ''}</td>
        <td class="num">${pls.reduce((s, x) => s + x.qty, 0)}</td><td>${pls.map(x => `${x.qty} on ${date(x.date)}`).join(', ')}</td></tr>`).join('')}
    </tbody></table>
    <p class="small muted">Production finishes ${date(d.productionFinish)}, dispatch ${date(d.dispatchDate)}, delivery ${date(d.earliestDelivery)}
      (${d.transitDays} day transit). Binding constraint: ${esc(d.bindingConstraint)}.${p.saferDate ? ` With one working day of buffer: ${date(p.saferDate)}.` : ''}</p>
    ${reasoning(a)}
  </div>`;
}

function enquiry(a, status) {
  const r = a.answer;
  return `<div class="card">
    <div class="title"><strong>${esc(r.accountName || r.from)} <span class="muted" style="font-weight:400">${esc(r.mailId)}</span></strong>${outcomePill(r.outcome)}</div>
    <p>${esc(r.headline)}</p>
    ${r.lines.length ? `<table class="small"><thead><tr><th>Product</th><th class="num">Qty</th><th class="num">Price</th><th>Asked</th><th>Quoted</th><th>Slack</th></tr></thead><tbody>
      ${r.lines.map(l => `<tr><td>${esc(l.sku)}</td><td class="num">${l.qty}</td><td class="num">${inr(l.unitPrice)}</td><td class="nw">${date(l.requestedDate)}</td>
        <td class="nw">${date(l.promisedDate)} ${l.requestedSupported ? pill('as asked', 'good') : l.requestedDate ? pill('later', 'warn') : ''}</td>
        <td>${l.slackWorkingDays ?? 'n/a'}</td></tr>`).join('')}</tbody></table>` : ''}
    ${r.missing.length ? `<p class="small"><strong>Missing:</strong> ${esc(r.missing.join('; '))}</p>` : ''}
    ${r.actionIds.length ? `<p class="small muted">${r.actionIds.map(id => status[id] ? `${esc(id)} ${words(status[id].type)}: ${words(status[id].status)}` : esc(id)).join('; ')}</p>` : ''}
    <details><summary>Confidence and reasoning</summary>${reasoning(a)}</details>
  </div>`;
}

function nextDay(today, day) {
  const t = new Date(today + 'T00:00:00Z');
  let d = new Date(Date.UTC(t.getUTCFullYear(), t.getUTCMonth(), day));
  if (d < t) d = new Date(Date.UTC(t.getUTCFullYear(), t.getUTCMonth() + 1, day));
  return d.toISOString().slice(0, 10);
}
