import { get, post, qs } from '../api.js';
import { esc, inr, date, words, statePill, actionCard, suggestionCard, reasoning, toast } from '../ui.js';

let lastReplan = null;
let lastSync = null;

// Planning: where every order stands, what the lines carry, what to do when production moves.
export async function render(root, ctx) {
  const v = await get('/api/views/planner');
  const mine = v.pending.filter(a => a.requiredRole === 'PLANNER');
  const orderSuggestions = v.suggestions.filter(s => s.subject.startsWith('ORDER:'));
  root.innerHTML = `
  <div class="pagehead"><h1>Planning</h1>
    <div class="tools"><button id="sync">Sync customer status</button><button id="replan" class="primary">Re-plan now</button></div></div>
  <div id="replanOut">${lastReplan ? replanResult(lastReplan) : ''}</div>
  <div id="syncOut">${lastSync ? syncResult(lastSync) : ''}</div>
  <div class="cols">
    <div>
      <section class="panel" aria-labelledby="orders-h">
        <h2 id="orders-h">Open orders</h2>
        <table><thead><tr><th>Order</th><th>Customer</th><th>What</th><th class="num">Value</th><th>Promised</th><th>Estimate</th><th class="num">Slack</th><th>State</th><th>Customer sees</th></tr></thead>
        <tbody>${v.orders.map(o => `<tr>
          <td class="nw">${esc(o.orderId)}${o.customerPo ? `<div class="muted small">${esc(o.customerPo)}</div>` : ''}</td>
          <td>${esc(o.account)}</td><td class="nw">${esc(o.product)}</td>
          <td class="num"><button class="link" data-explain="value:${esc(o.orderId)}">${esc(o.value)}</button></td>
          <td class="nw">${date(o.committed)}</td>
          <td class="nw"><button class="link" data-explain="eta:${esc(o.orderId)}">${date(o.estimate)}</button></td>
          <td class="num"><button class="link" data-explain="slack:${esc(o.orderId)}">${o.slack ?? 'n/a'}</button></td>
          <td>${statePill(o.state)}</td><td class="muted small" style="min-width:9rem">${esc(o.customerSees)}</td></tr>`).join('')}</tbody></table>
      </section>
      <section class="panel" aria-labelledby="load-h">
        <h2 id="load-h">Line load, next 12 working days</h2>
        <p class="muted small">Free minutes per day; the bar is the booked share. Red cells lost capacity. Select a cell to see which work orders hold it.</p>
        ${board(v.lines)}
      </section>
    </div>
    <div>
      <section class="panel" aria-labelledby="mine-h">
        <h2 id="mine-h">Waiting for a planner</h2>
        ${mine.length ? mine.map(a => actionCard(a, ctx.me)).join('') : '<p class="empty">Nothing waits for planning right now.</p>'}
      </section>
      <section class="panel" aria-labelledby="sugg-h">
        <h2 id="sugg-h">Recovery suggestions</h2>
        ${orderSuggestions.length ? orderSuggestions.map(s => suggestionCard(s, ctx.me)).join('')
          : '<p class="empty">No orders need recovery. Suggestions appear after a re-plan finds an order at risk.</p>'}
      </section>
    </div>
  </div>`;

  root.querySelector('#replan').onclick = async e => {
    e.target.disabled = true;
    try { lastReplan = await post('/api/replan?' + qs({ trigger: 'planner request' })); toast(lastReplan.headline); }
    catch (err) { toast(err.message); }
    ctx.refresh();
  };
  root.querySelector('#sync').onclick = async e => {
    e.target.disabled = true;
    try { lastSync = await post('/api/status/sync'); toast(lastSync.length ? `${lastSync.length} status change(s)` : 'Customer status already matches the plan'); }
    catch (err) { toast(err.message); }
    ctx.refresh();
  };
  root.querySelectorAll('[data-close]').forEach(b => b.onclick = () => {
    if (b.dataset.close === 'replan') lastReplan = null; else lastSync = null;
    ctx.refresh();
  });
}

function board(lines) {
  const days = lines[0]?.days ?? [];
  return `<div style="overflow-x:auto"><div class="board">
    <div></div>${days.map(d => `<div class="day">${date(d.date)}</div>`).join('')}
    ${lines.map(l => `<div class="lane">${esc(l.lineId)} ${esc(l.name)}</div>` + l.days.map(d => {
      const share = d.available ? Math.min(1, d.booked / d.available) : 0;
      const cls = d.available === 0 ? (d.note ? 'down' : 'closed') : share >= .97 ? 'full' : '';
      const text = d.available === 0 ? (d.note ? 'lost' : 'closed') : d.free;
      return `<button class="cell ${cls}" data-explain="free:${esc(l.lineId)}:${d.date}" title="${esc(d.note || '')}"
        aria-label="${esc(l.lineId)} ${date(d.date)}: ${d.available === 0 ? esc(d.note || 'no shift') : d.free + ' free minutes'}">
        <span class="fill" style="height:${Math.round(share * 100)}%"></span><span class="free">${text}</span></button>`;
    }).join('')).join('')}</div></div>`;
}

function replanResult(a) {
  const r = a.answer;
  const moved = r.impacts.filter(i => i.atRisk || i.previousEstimate !== i.currentEstimate);
  return `<section class="panel"><div class="title" style="display:flex;justify-content:space-between"><h2>${esc(a.headline)}</h2><button data-close="replan">Close</button></div>
    ${r.events.length ? `<p class="small muted">Planning events read: ${esc(r.events.join(' | '))}</p>` : ''}
    <table><thead><tr><th>Order</th><th>Customer</th><th class="num">Value</th><th>Promised</th><th>Was</th><th>Now</th><th>State</th></tr></thead><tbody>
      ${moved.map(i => `<tr><td>${esc(i.orderId)}</td><td>${esc(i.account)}</td><td class="num">${inr(i.value)}</td><td class="nw">${date(i.committed)}</td>
        <td class="nw">${date(i.previousEstimate)}</td><td class="nw">${date(i.currentEstimate)}</td>
        <td>${i.atRisk ? statePill(i.lateDays > 0 ? 'LATE' : 'AT_RISK') + ` ${i.lateDays} day(s)` : statePill('ON_TRACK')}</td></tr>`).join('')}</tbody></table>
    ${Object.entries(r.options).map(([order, opts]) => `<h3>Options for ${esc(order)}, compared with doing nothing</h3>
      <table class="small"><thead><tr><th>Option</th><th>Delivers</th><th class="num">Cost</th><th class="num">Expected loss</th><th class="num">Net vs nothing</th><th>What it does</th></tr></thead><tbody>
      ${opts.map(o => `<tr><td class="nw">${words(o.kind)}</td><td class="nw">${date(o.newEstimate)}</td><td class="num">${inr(o.cost)}</td>
        <td class="num">${inr(o.expectedLoss)}</td><td class="num ${o.netBenefit > 0 ? 'net' : ''}">${inr(o.netBenefit)}</td>
        <td>${esc(o.kind === 'DO_NOTHING' ? 'Keep the current plan' : o.description)}</td></tr>`).join('')}</tbody></table>`).join('')}
    <details><summary>Confidence and reasoning</summary>${reasoning(a)}</details></section>`;
}

function syncResult(changes) {
  return `<section class="panel"><div class="title" style="display:flex;justify-content:space-between"><h2>Customer status sync</h2><button data-close="sync">Close</button></div>
    ${changes.length ? `<table class="small"><thead><tr><th>Order</th><th>Customer saw</th><th>Plan says</th><th>Outcome</th></tr></thead><tbody>
      ${changes.map(c => `<tr><td>${esc(c.orderId)} <span class="muted">${esc(c.account)}</span></td><td>${esc(c.customerSaw)}</td><td>${esc(c.nowTrue)}</td>
        <td>${esc(c.outcome)}${c.actionId ? ` (${esc(c.actionId)})` : ''}</td></tr>`).join('')}</tbody></table>`
      : '<p class="empty">Every customer already sees what the plan supports.</p>'}</section>`;
}
