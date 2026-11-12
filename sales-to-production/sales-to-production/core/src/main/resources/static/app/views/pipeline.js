import { get } from '../api.js';
import { esc, inr, words, suggestionCard, reasoning } from '../ui.js';

const SIGNALS = {
  daysSilent: v => `${v} day(s) since the customer last replied`,
  priceGapPct: v => `${v}% above the price they last paid`,
  deliveryGapDays: v => `${v} day(s) later than they asked`,
  competitorMentioned: v => v ? 'a competitor was mentioned' : 'no competitor mentioned',
  validityExpiring: v => v ? 'quote lapses within 3 days' : 'validity not close',
  accountWinRate: v => `account win rate ${Math.round(v * 100)}%`,
};

// Pipeline: open quotations ranked by expected loss, one specific action each.
export async function render(root, ctx) {
  const a = await get('/api/quotations/at-risk');
  const r = a.answer;
  const max = Math.max(1, ...r.ranked.map(q => q.expectedLoss));
  root.innerHTML = `
  <div class="pagehead"><h1>Pipeline</h1><span class="muted">${esc(a.headline)}</span></div>
  <div class="cols">
    <div>
      <section class="panel" aria-labelledby="rank-h">
        <h2 id="rank-h">Open quotations, most value at risk first</h2>
        <table><thead><tr><th>#</th><th>Quotation</th><th>Customer</th><th class="num">Value</th><th>Chance of losing it</th><th class="num">Value at risk</th><th>Main driver</th></tr></thead><tbody>
        ${r.ranked.map(q => `<tr>
          <td>${q.rank}</td><td>${esc(q.quotationId)}</td><td>${esc(q.account)}</td><td class="num">${inr(q.value)}</td>
          <td><div style="display:flex;gap:.5rem;align-items:center"><div class="bar" style="flex:1"><i style="width:${Math.round(q.pLoss * 100)}%"></i></div><span class="nw">${Math.round(q.pLoss * 100)}%</span></div></td>
          <td class="num"><div class="bar" style="margin-bottom:.2rem"><i style="width:${Math.round(q.expectedLoss / max * 100)}%;background:var(--amber)"></i></div>${inr(q.expectedLoss)}</td>
          <td class="small">${esc(driver(q))}</td></tr>`).join('')}</tbody></table>
      </section>
      ${r.ranked.map(q => `<section class="panel"><h2>${esc(q.quotationId)}, ${esc(q.account)}</h2>
        <p class="small muted">${Object.entries(q.signals).map(([k, v]) => SIGNALS[k] ? SIGNALS[k](v) : `${k} ${v}`).join('; ')}</p>
        ${q.suggestion ? suggestionCard(q.suggestion, ctx.me) : '<p class="empty">No action beats doing nothing for this quotation.</p>'}</section>`).join('')}
    </div>
    <div>
      <section class="panel" aria-labelledby="model-h">
        <h2 id="model-h">How far to trust this ranking</h2>
        <p>The loss model is scored against ${r.calibration.samples} closed quotations: Brier score <strong>${r.calibration.brier.toFixed(3)}</strong>
          against <strong>${r.calibration.baselineBrier.toFixed(3)}</strong> for always guessing the average loss rate. Lower is better.</p>
        ${reasoning(a)}
      </section>
    </div>
  </div>`;
}

function driver(q) {
  const key = q.mainDriver.split(' ')[0];
  return SIGNALS[key] ? SIGNALS[key](q.signals[key]) : words(q.mainDriver);
}
