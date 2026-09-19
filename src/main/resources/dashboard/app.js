import { BusinessWorkspace } from './workspace.js';
('use strict');
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const money = (v, currency = 'USD') =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency, maximumFractionDigits: 2 }).format(
    (v || 0) / 100,
  );
const esc = (value) =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  );
const human = (value) =>
  String(value || '')
    .replaceAll('_', ' ')
    .toLowerCase();
let catalog = [],
  dataset,
  selected,
  run = null,
  tab = 'timeline',
  filter = 'All',
  busy = false,
  workspace;
const terminal = new Set(['SETTLED', 'REJECTED', 'CANCELED']);
function pill(text, type = 'neutral') {
  return `<span class="pill ${type}">${esc(text)}</span>`;
}
function stateTone(state) {
  return ['SETTLED', 'MATCHED', 'ALLOCATED', 'ALREADY_ALLOCATED', 'NO_CASH_EXPECTED'].includes(
    state,
  )
    ? 'good'
    : ['UNKNOWN', 'DISPATCHING', 'PENDING', 'UNAPPLIED', 'SHORTFALL'].includes(state)
      ? 'warn'
      : ['READY'].includes(state)
        ? 'neutral'
        : 'bad';
}
function metric(label, value, note) {
  return `<div class="metric"><div class="metric-label">${esc(label)}</div><div class="metric-value">${esc(value)}</div><div class="metric-note">${esc(note)}</div></div>`;
}
function empty(title, text) {
  return `<div class="empty"><div class="empty-symbol">◎</div><h3>${esc(title)}</h3><p>${esc(text)}</p></div>`;
}
function table(headers, rows) {
  if (!rows.length) return '<p class="muted">No records yet.</p>';
  return `<div class="table-scroll"><table><thead><tr>${headers.map((x) => `<th>${esc(x)}</th>`).join('')}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((x) => `<td>${x}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
}
function raw(title, value) {
  return `<details><summary>${esc(title)}</summary><pre>${esc(JSON.stringify(value, null, 2))}</pre></details>`;
}
function showError(error) {
  $('#error').textContent = error.message || String(error);
  $('#error').classList.remove('hidden');
}
async function api(path, body) {
  const response = await fetch(
    path,
    body === undefined
      ? {}
      : {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Capital-Lab': 'local-demo' },
          body: JSON.stringify(body),
        },
  );
  const result = await response.json();
  if (!response.ok) {
    const error = Error(result.error || `Request failed (${response.status})`);
    error.status = response.status;
    error.requestId = result.requestId;
    throw error;
  }
  return result;
}
async function action(label, fn) {
  if (busy) return;
  busy = true;
  document.body.classList.add('busy');
  $('#busy-status').textContent = label;
  $('#error').classList.add('hidden');
  renderControls();
  try {
    await fn();
  } catch (error) {
    showError(error);
  } finally {
    busy = false;
    document.body.classList.remove('busy');
    $('#busy-status').textContent = '';
    renderControls();
  }
}
function showView(view) {
  window.scrollTo(0, 0);
  $$('.view').forEach((node) => node.classList.toggle('hidden', node.id !== view));
  $$('.nav-item').forEach((node) => node.classList.toggle('active', node.dataset.view === view));
  $('#breadcrumb').textContent = {
    developer: 'Developer payouts',
    operations: 'Finance operations',
    workbench: 'Engineering lab',
    dataset: 'Public data replay',
    architecture: 'How it connects',
  }[view];
}
function selectScenario(id) {
  if (busy) return;
  selected = catalog.find((x) => x.id === id);
  run = null;
  tab = 'timeline';
  history.replaceState(null, '', location.pathname);
  if (id === 'public-data') showView('dataset');
  renderLibrary();
  renderRun();
}
function renderLibrary() {
  const query = $('#scenario-search').value.toLowerCase();
  $('#scenario-count').textContent = catalog.length;
  $('#scenario-list').innerHTML = catalog
    .filter(
      (s) =>
        (filter === 'All' ||
          (filter === 'Failures'
            ? ['Recovery', 'Risk monitoring', 'Fraud review', 'Bank integration'].includes(
                s.category,
              )
            : ['Reconciliation', 'Concurrency'].includes(s.category))) &&
        (s.title + ' ' + s.description + ' ' + s.category).toLowerCase().includes(query),
    )
    .map(
      (s) =>
        `<button class="scenario-card ${selected?.id === s.id ? 'active' : ''}" data-scenario="${esc(s.id)}"><span class="category">${esc(s.category)}</span><strong>${esc(s.title)}</strong><span class="step-count">${s.steps.length} steps <span aria-hidden="true">↗</span></span></button>`,
    )
    .join('');
  $$('.scenario-card').forEach((node) => {
    node.disabled = busy;
    node.onclick = () => selectScenario(node.dataset.scenario);
  });
}
function renderControls() {
  $('#start').disabled = busy;
  $('#start').innerHTML = run ? 'New isolated run ↗' : 'Start scenario ↗';
  $('#next').disabled = busy || !run || run.complete || run.archived;
  $('#run-all').disabled = busy || !run || run.complete || run.archived;
  $('#export').disabled = busy || !run;
  $('#replay-data').disabled = busy;
  $$('.scenario-card').forEach((n) => (n.disabled = busy));
  $('#next').textContent =
    run && !run.complete ? `Next: ${run.nextStep + 1} / ${selected.steps.length} →` : 'Next step →';
}
function renderRun() {
  if (!selected) return;
  $('#scenario-category').textContent = selected.category.toUpperCase();
  $('#scenario-title').textContent = selected.title;
  $('#scenario-description').textContent =
    selected.description +
    (run?.datasetDate ? ` Source day: ${run.datasetDate}. GBP → simulated USD at 1.25.` : '');
  const s = run?.state,
    advance = s?.advances[0],
    developer = s?.developers[0],
    pool = s?.pools[0];
  $('#run-status').textContent = run
    ? run.archived
      ? 'ARCHIVED · READ ONLY'
      : run.complete
        ? 'WALKTHROUGH COMPLETE'
        : advance?.state || 'IN PROGRESS'
    : 'READY TO EXPLORE';
  $('#run-status').className =
    'pill ' + (run?.complete ? 'good' : stateTone(advance?.state || 'READY'));
  $('#progress-label').textContent = run
    ? `${run.nextStep} of ${selected.steps.length} steps completed`
    : 'No run started';
  $('#step-track').innerHTML = selected.steps
    .map(
      (title, index) =>
        `<div class="step-segment ${(run?.nextStep || 0) > index ? 'done' : ''}" title="${index + 1}. ${esc(title)}"></div>`,
    )
    .join('');
  $('#metrics').innerHTML =
    metric('Principal reserved', money(developer?.reserved_cents), 'Committed, not yet funded') +
    metric(
      'Outstanding principal',
      money(developer?.outstanding_cents),
      run ? 'Includes opening fixture exposure' : 'Across this receivables pool',
    ) +
    metric('Bank funding cash', money(s?.bankCashCents), 'Independent simulated balance') +
    metric(
      'Risk hold',
      developer?.hold ? 'Active' : 'Clear',
      developer?.hold ? 'Recovery is query-only' : 'Dispatch still revalidates',
    );
  const flow = [
    [
      '01',
      'Source',
      run?.datasetDate ? 'Public data replay' : 'Synthetic report',
      'decision',
      !!run,
    ],
    [
      '02',
      'Risk',
      developer?.hold ? 'Hold active' : 'Eligibility checks',
      'decision',
      !!run?.assessment,
    ],
    [
      '03',
      'Reserve',
      developer?.reserved_cents ? money(developer.reserved_cents) : 'Atomic transaction',
      'bank',
      !!advance,
    ],
    ['04', 'Bank', advance?.state || 'No operation', 'bank', !!s?.bank.length],
    ['05', 'Ledger', `${s?.ledger.length || 0} funding entries`, 'ledger', !!s?.ledger.length],
    [
      '06',
      'Reconcile',
      s?.reconciliation[0]?.status?.replaceAll('_', ' ') || 'Awaiting evidence',
      'reconciliation',
      !!s?.reconciliation.length,
    ],
  ];
  $('#flow').innerHTML = flow
    .map(
      (n) =>
        `<button class="flow-node ${n[5] ? 'engaged' : ''}" data-inspect="${n[3]}"><span class="node-number">${n[0]}</span><b>${n[1]}</b><span>${esc(n[2])}</span></button>`,
    )
    .join('');
  $$('[data-inspect]').forEach(
    (n) =>
      (n.onclick = () => {
        tab = n.dataset.inspect;
        renderEvidence();
      }),
  );
  renderEvidence();
  renderControls();
}
function renderEvidence() {
  $$('.tabs button').forEach((n) => {
    n.classList.toggle('selected', n.dataset.tab === tab);
    n.setAttribute('aria-selected', n.dataset.tab === tab);
  });
  const target = $('#evidence-body');
  if (!run) {
    target.innerHTML = empty(
      'Every step leaves evidence.',
      'Start a scenario to see the decision, bank observations, and ledger entries appear here.',
    );
    return;
  }
  const s = run.state,
    a = run.assessment,
    developer = s.developers[0],
    pool = s.pools[0];
  if (tab === 'timeline') {
    target.innerHTML = run.events.length
      ? run.events
          .map(
            (event) =>
              `<article class="event"><span class="event-number">${event.number}</span><div><div class="event-heading"><h3>${esc(event.title)}</h3><time>${esc(event.time.slice(11, 19))} UTC · demo clock</time></div><p>${esc(event.message)}</p><div class="event-summary">${event.state.advances[0] ? pill(event.state.advances[0].state, stateTone(event.state.advances[0].state)) : ''}<span>Reserved ${money(event.state.developers[0].reserved_cents)}</span><span>${event.state.bank.filter((b) => b.state === 'SETTLED').length} bank payments</span><span>${event.state.ledger.length} funding entries</span></div></div></article>`,
          )
          .join('')
      : empty(
          'Scenario initialized.',
          'The database is seeded. Next, evaluate the source and risk signals.',
        );
  } else if (tab === 'decision') {
    target.innerHTML =
      (a
        ? `<div class="panel-title"><h2>Eligibility quote</h2>${pill(a.status, a.status === 'ELIGIBLE' ? 'good' : 'warn')}</div>${developer.hold ? '<div class="callout warning"><b>Authorization is held.</b> A positive arithmetic quote does not override the active risk hold.</div>' : ''}${table(
            ['Input / result', 'Value'],
            [
              ['Net proceeds', money(a.snapshot.netProceedsCents)],
              ['Existing exposure at assessment', money(a.snapshot.existingExposureCents)],
              ['80% limit, capped by borrower ceiling', money(a.effectiveLimitCents)],
              ['Additional quoted principal', money(a.eligiblePrincipalCents)],
              ['Fixed fee (2.5%)', money(a.feeCents)],
              ['Net amount received', money(a.netCashCents)],
              ['Excess exposure', money(a.excessExposureCents)],
              ['Report coverage', esc(a.snapshot.reportThroughDate)],
              ['Expected coverage', esc(a.context.expectedReportThroughDate)],
            ],
          )}<p class="muted">Reasons: ${a.reasons.map((x) => esc(human(x))).join(' · ')}</p>`
        : empty(
            'Assessment not run yet.',
            'The first step calculates eligibility and evaluates monitoring rules.',
          )) +
      `<h3>Monitoring & fraud review</h3>${run.signals.length ? run.signals.map((signal) => `<div class="callout ${signal.severity === 'HOLD' ? 'warning' : ''}"><b>${esc(human(signal.code))}</b> · ${esc(signal.component)}<br>${esc(signal.explanation)}</div>`).join('') : '<p class="muted">No review signals in this scenario so far.</p>'}<p class="muted">Illustrative thresholds: cancellations ≥ 10% of gross; sales ≥ 3× trailing 7 observed days. Signals are not proof of fraud.</p>${table(
        ['Current capacity controls', 'Value'],
        [
          ['Lifetime funded', money(pool.funded_lifetime_cents)],
          ['Outstanding', money(pool.outstanding_cents)],
          ['Reserved', money(pool.reserved_cents)],
          ['Final collection closed pool', pool.closed ? 'Yes' : 'No'],
        ],
      )}${raw('Inspect full quote and risk inputs', { assessment: a, signals: run.signals, riskInputs: run.riskInputs })}`;
  } else if (tab === 'bank') {
    target.innerHTML = `<div class="panel-title"><h2>Bank observations</h2><span class="muted">${s.bankPostRequests} POST · ${s.bankLookupRequests} lookup</span></div>${table(
      ['Transfer', 'Net cash', 'State', 'Submissions'],
      s.bank.map((b) => [
        `<span class="key" title="${esc(b.transfer_id)}">${esc(b.transfer_id.slice(0, 17))}…</span>`,
        money(b.amount_cents),
        pill(b.state, stateTone(b.state)),
        esc(b.submit_count),
      ]),
    )}<div class="callout">An unknown outcome keeps the reservation. During a hold, recovery queries the same key and never resubmits. A completed bank debit can be posted even while held.</div><h3>Durable dispatch intent</h3>${table(
      ['Operation', 'Attempts', 'Complete', 'Next eligible retry'],
      s.outbox.map((o) => [
        esc(o.advance_id.slice(0, 8)),
        esc(o.attempts),
        o.done ? 'Yes' : 'No',
        esc(o.next_attempt_at),
      ]),
    )}${raw('Inspect frozen commands and bank records', { advances: s.advances, bank: s.bank, outbox: s.outbox })}`;
  } else if (tab === 'ledger') {
    const entries = [...s.ledger, ...s.collectionLedger];
    const debits = entries.filter((e) => e.side === 'DEBIT').reduce((n, e) => n + e.cents, 0),
      credits = entries.filter((e) => e.side === 'CREDIT').reduce((n, e) => n + e.cents, 0);
    target.innerHTML = `<div class="panel-title"><h2>Posted entries</h2>${pill(entries.length ? (debits === credits ? 'BALANCED' : 'IMBALANCED') : 'NO POSTINGS', entries.length && debits === credits ? 'good' : 'neutral')}</div><p class="muted">New funding and collection entries only. Opening balances are seeded controls, not a full opening general ledger.</p>${table(
      ['Journal', 'Account', 'Debit', 'Credit'],
      entries.map((e) => [
        esc((e.posting_key || e.receipt_id).slice(0, 21)),
        esc(human(e.account)),
        e.side === 'DEBIT' ? money(e.cents) : '—',
        e.side === 'CREDIT' ? money(e.cents) : '—',
      ]),
    )}<div class="stat-row"><span>Posted debits / credits</span><b>${money(debits)} / ${money(credits)}</b></div><div class="callout">The 2.5% fee is withheld once at funding and deferred. Collection repays principal without charging that fee again. Residual money remains payable; it is not added to available funding cash.</div>${raw('Inspect ledger and collections', { funding: s.ledger, collections: s.collections, collectionEntries: s.collectionLedger })}`;
  } else {
    target.innerHTML = `<h2>Payment → bank → ledger</h2>${s.statementFaultInjected ? '<div class="callout warning">Fault injected: a statement copy differs by $1. The actual simulated bank record was not changed.</div>' : ''}${table(
      ['Payment', 'Result', 'Expected net', 'Statement net'],
      s.reconciliation.map((r) => [
        esc(r.key.slice(0, 16)) + '…',
        pill(r.status, stateTone(r.status)),
        money(r.expectedCents),
        r.observedCents == null ? '—' : money(r.observedCents),
      ]),
    )}${s.reconciliation.map((r) => `<p class="muted">${esc(r.explanation)}</p>`).join('')}<div class="stat-row"><span>Local funding cash / bank cash</span><b>${money(s.treasury[0].cash_cents)} / ${money(s.bankCashCents)}</b></div>${s.treasury[0].cash_cents !== s.bankCashCents ? '<div class="callout warning">Cash controls differ. Resolve the existing bank operation before treating that cash as available.</div>' : ''}<h3>Final store collection</h3>${run.collection ? `${pill(run.collection.status, stateTone(run.collection.status))}<p class="muted">${esc(run.collection.explanation)}</p>${table(['Principal repaid', 'Developer residual payable'], [[money(run.collection.principalCents), money(run.collection.residualCents)]])}` : '<p class="muted">No store collection has been allocated. Choose a collection scenario to exercise this path.</p>'}<p class="muted">Bounded demo: one final report and receipt per pool. Partial/aggregated receipts, residual bank payouts, returns, and cash sweeps remain architecture work.</p>${raw('Inspect reconciliation evidence', { paymentMatches: s.reconciliation, storeReceipts: s.storeReceipts, allocation: run.collection })}`;
  }
}
async function startRun(date) {
  const body = { scenario: selected.id };
  if (selected.id === 'public-data') body.datasetDate = date || $('#dataset-date').value;
  run = await api('/api/runs', body);
  await loadHistory();
  history.replaceState(null, '', `#run=${run.id}`);
  renderRun();
}
async function nextStep() {
  if (!run || run.complete || run.archived) return;
  run = await api(`/api/runs/${run.id}/step`, { expectedStep: run.nextStep });
  renderRun();
}
function renderDataset() {
  $('#dataset-summary').innerHTML =
    metric(
      'Source transaction lines',
      dataset.sourceRows.toLocaleString(),
      'Original UCI workbook',
    ) +
    metric('Observed days', dataset.days.length, 'Dec 2010 – Dec 2011') +
    metric('Source currency', 'GBP', 'Retained in the chart') +
    metric('Data license', 'CC BY 4.0', 'Attribution included');
  $('#dataset-date').innerHTML = dataset.days
    .map((d) => `<option value="${esc(d.date)}">${esc(d.date)}</option>`)
    .join('');
  $('#dataset-date').value = '2011-01-10';
  const max = Math.max(...dataset.days.map((d) => Math.max(d.grossMinor, d.cancellationsMinor)));
  const chart = $('#chart');
  chart.replaceChildren();
  dataset.days.forEach((d) => {
    const bar = document.createElement('button');
    bar.className = 'bar';
    bar.dataset.date = d.date;
    bar.title = `${d.date}: gross ${money(d.grossMinor, 'GBP')}, cancellations ${money(d.cancellationsMinor, 'GBP')}`;
    bar.setAttribute('aria-label', bar.title);
    const gross = document.createElement('span');
    gross.className = 'gross';
    gross.style.height = `${(d.grossMinor / max) * 100}%`;
    const cancel = document.createElement('span');
    cancel.className = 'cancellation';
    cancel.style.height = `${(d.cancellationsMinor / max) * 100}%`;
    bar.append(gross, cancel);
    bar.onclick = () => {
      $('#dataset-date').value = d.date;
      renderDay();
    };
    chart.append(bar);
  });
  $('#dataset-attribution').innerHTML =
    `<b>Attribution</b><br>${esc(dataset.citation)}<br><a href="${esc(dataset.sourceUrl)}" target="_blank" rel="noreferrer">Original dataset ↗</a> · <a href="${esc(dataset.licenseUrl)}" target="_blank" rel="noreferrer">CC BY 4.0 ↗</a><p>${dataset.excludedRows.toLocaleString()} rows excluded for nonpositive price or zero quantity. Customer IDs omitted. Daily totals are derived; full transformation and source hashes are in the exported metadata.</p>`;
  renderDay();
}
function renderDay() {
  const d = dataset.days.find((d) => d.date === $('#dataset-date').value);
  if (!d) return;
  $$('.bar').forEach((b) => b.classList.toggle('selected', b.dataset.date === d.date));
  $('#selected-day').innerHTML = [
    ['Gross sales', money(d.grossMinor, 'GBP')],
    ['Cancellations', money(d.cancellationsMinor, 'GBP')],
    ['Net observed amount', money(d.netMinor, 'GBP')],
    ['Sale / cancellation lines', `${d.saleLines} / ${d.cancellationLines}`],
  ]
    .map(([k, v]) => `<div class="stat-row"><span>${esc(k)}</span><b>${esc(v)}</b></div>`)
    .join('');
}
$$('.nav-item').forEach(
  (node) =>
    (node.onclick = () => {
      showView(node.dataset.view);
      if (node.dataset.view === 'operations') workspace?.refreshFinance();
    }),
);
$$('.tabs button').forEach(
  (node) =>
    (node.onclick = () => {
      tab = node.dataset.tab;
      renderEvidence();
    }),
);
$('#scenario-search').oninput = renderLibrary;
$('#filters').innerHTML = ['All', 'Failures', 'Integrity']
  .map((name) => `<button class="${name === 'All' ? 'active' : ''}">${name}</button>`)
  .join('');
$$('#filters button').forEach(
  (node) =>
    (node.onclick = () => {
      filter = node.textContent;
      $$('#filters button').forEach((n) => n.classList.toggle('active', n === node));
      renderLibrary();
    }),
);
$('#start').onclick = () => action('Creating isolated scenario…', () => startRun());
$('#next').onclick = () =>
  action('Running next step… HTTP fault scenarios may take a few seconds.', nextStep);
$('#run-all').onclick = () =>
  action('Running scenario… each step is saved as evidence.', async () => {
    while (run && !run.complete && !run.archived) await nextStep();
  });
$('#dataset-date').onchange = renderDay;
$('#replay-data').onclick = () =>
  action('Preparing public-data replay…', async () => {
    selected = catalog.find((x) => x.id === 'public-data');
    run = null;
    tab = 'timeline';
    renderLibrary();
    showView('workbench');
    await startRun($('#dataset-date').value);
  });
$('#export').onclick = () => {
  if (!run) return;
  const evidence = {
    ...run,
    datasetProvenance: run.datasetDate
      ? { ...dataset, days: [dataset.days.find((d) => d.date === run.datasetDate)] }
      : null,
  };
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(evidence, null, 2)], { type: 'application/json' }),
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = `capital-lab-${selected.id}-${run.id.slice(0, 8)}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
(async () => {
  try {
    [catalog, dataset] = await Promise.all([api('/api/catalog'), api('/api/dataset')]);
    selected = catalog.find((x) => x.id === 'lost-held');
    const saved = location.hash.match(/^#run=([a-f0-9]{32})$/);
    if (saved) {
      try {
        run = await api(`/api/runs/${saved[1]}`);
        selected = catalog.find((x) => x.id === run.scenario.id);
      } catch (error) {
        history.replaceState(null, '', location.pathname);
        showError(error);
      }
    }
    renderLibrary();
    renderRun();
    renderDataset();
    await loadHistory();
    workspace = new BusinessWorkspace({ api, showView });
    await workspace.init();
    showView(saved ? 'workbench' : 'developer');
  } catch (error) {
    showError(error);
  }
})();

async function loadHistory() {
  const list = await api('/api/runs');
  $('#run-history').innerHTML =
    '<option value="">Run history</option>' +
    list
      .map(
        (item) =>
          `<option value="${esc(item.id)}">${item.archived ? 'Archive' : 'Active'} · ${esc(item.title)} · ${item.id.slice(0, 6)}</option>`,
      )
      .join('');
}
$('#run-history').onchange = () =>
  action('Loading run evidence…', async () => {
    const id = $('#run-history').value;
    if (!id) return;
    run = await api(`/api/runs/${id}`);
    selected = catalog.find((s) => s.id === run.scenario.id);
    history.replaceState(null, '', `#run=${id}`);
    renderLibrary();
    renderRun();
  });
