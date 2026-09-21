const $ = (selector) => document.querySelector(selector);
const escape = (value) =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  );
const money = (cents = 0) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(cents / 100);
const label = (value) =>
  String(value || '')
    .replaceAll('_', ' ')
    .toLowerCase();
const badge = (value, tone = 'neutral') => `<span class="pill ${tone}">${escape(value)}</span>`;
const tone = (value) =>
  ['SETTLED', 'MATCHED', 'ALLOCATED', 'ALREADY_ALLOCATED', 'NO_CASH_EXPECTED'].includes(value)
    ? 'good'
    : ['UNKNOWN', 'DISPATCHING', 'PENDING', 'UNAPPLIED', 'SHORTFALL'].includes(value)
      ? 'warn'
      : value === 'READY'
        ? 'neutral'
        : 'bad';
const metric = (title, value, caption) =>
  `<div class="metric"><div class="metric-label">${escape(title)}</div><div class="metric-value">${escape(value)}</div><div class="metric-note">${escape(caption)}</div></div>`;
const table = (headings, rows) =>
  rows.length
    ? `<div class="table-scroll"><table><thead><tr>${headings.map((h) => `<th>${escape(h)}</th>`).join('')}</tr></thead><tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`
    : '<div class="ops-empty">No records yet. Request an advance in Developer payouts, or select a guided scenario.</div>';

/** Product views read the same backend evidence as the engineering lab; no parallel money model. */
export class BusinessWorkspace {
  constructor({ api, showView }) {
    this.api = api;
    this.showView = showView;
    this.account = null;
    this.finance = null;
    this.financeTab = 'payouts';
    this.busy = false;
    $('#ops-refresh').onclick = () => this.refreshFinance();
    $('#ops-run').onchange = () => this.refreshFinance($('#ops-run').value);
  }
  async init() {
    const saved = localStorage.getItem('capital-lab-account');
    if (saved && /^[a-f0-9]{32}$/.test(saved)) {
      try {
        this.account = await this.api(`/api/runs/${saved}`);
      } catch (error) {
        if (error.status !== 404) throw error;
        localStorage.removeItem('capital-lab-account');
      }
    }
    if (!this.account) await this.newAccount();
    else {
      if (!this.account.archived && this.account.nextStep === 0) {
        this.account = await this.api(`/api/runs/${this.account.id}/step`, { expectedStep: 0 });
      }
      this.renderDeveloper();
    }
  }
  async newAccount() {
    this.account = await this.api('/api/runs', { scenario: 'happy', purpose: 'workspace' });
    localStorage.setItem('capital-lab-account', this.account.id);
    // Assessment creates a quote only. Reservation/payment needs the user's Request advance click.
    this.account = await this.api(`/api/runs/${this.account.id}/step`, { expectedStep: 0 });
    this.renderDeveloper();
  }
  async requestAdvance() {
    if (this.busy || !this.account || this.account.archived || this.account.complete) return;
    this.busy = true;
    this.renderDeveloper();
    try {
      while (!this.account.complete) {
        this.account = await this.api(`/api/runs/${this.account.id}/step`, {
          expectedStep: this.account.nextStep,
        });
        this.renderDeveloper();
      }
    } catch (error) {
      // The bank may have executed. Refresh this identity; never create a replacement on an HTTP error.
      try {
        this.account = await this.api(`/api/runs/${this.account.id}`);
      } catch {
        /* Keep last observation visible. */
      }
      this.error(error);
    } finally {
      this.busy = false;
      this.renderDeveloper();
    }
  }
  error(error) {
    $('#error').textContent = error.message || 'Operation could not complete.';
    $('#error').classList.remove('hidden');
  }
  renderDeveloper() {
    const run = this.account;
    if (!run) return;
    const state = run.state,
      payout = state.advances[0],
      quote = run.assessment;
    const settled = payout?.state === 'SETTLED';
    const eligible =
      quote?.status === 'ELIGIBLE' && !state.developers[0].hold && !payout && !run.archived;
    const principal = payout?.principal_cents ?? quote?.eligiblePrincipalCents ?? 0;
    const fee = payout?.fee_cents ?? quote?.feeCents ?? 0;
    const net = payout?.cash_cents ?? quote?.netCashCents ?? 0;
    const rate = quote ? quote.policy.feeBasisPoints / 100 : '—';
    const advanceRate = quote ? quote.policy.advanceBasisPoints / 100 : '—';
    const completedUncertain =
      run.complete && payout && !['SETTLED', 'REJECTED', 'CANCELED'].includes(payout.state);
    const status = run.archived
      ? 'Archived demo account'
      : settled
        ? 'Payout sent'
        : payout
          ? label(payout.state)
          : 'Ready when you are';
    $('#developer-content').innerHTML = `
      <div class="developer-grid">
        <section class="panel payout-card">
          <div class="panel-title"><span class="eyebrow">${settled ? 'YOUR LATEST PAYOUT' : 'AVAILABLE ADVANCE'}</span>${badge(status, settled ? 'good' : payout ? 'warn' : 'neutral')}</div>
          <div class="payout-amount">${money(principal)}</div>
          <p>${settled ? 'Your advance has been recorded as paid by the simulated bank.' : 'Advance against the app-store proceeds already earned by your app.'}</p>
          <div class="payout-breakdown">
            <div><span>Advance amount</span><strong data-testid="advance-principal">${money(principal)}</strong></div>
            <div><span>Fixed fee <span class="fee-rate">${rate}%</span></span><strong data-testid="advance-fee">−${money(fee)}</strong></div>
            <div class="net-payout"><span>${settled ? 'Amount received' : 'You receive'}</span><strong data-testid="advance-net">${money(net)}</strong></div>
          </div>
          <p class="fee-explanation">One ${rate}% fee on the advance amount, deducted upfront. No second fee when store proceeds arrive.</p>
          <button id="request-advance" class="button primary payout-submit" ${this.busy || run.archived || run.complete || (!eligible && !payout) ? 'disabled' : ''}>${this.busy ? 'Processing your payout…' : settled ? '✓ Payout sent' : payout ? 'Check payout status' : `Request ${money(net)} payout →`}</button>
          ${completedUncertain ? '<div class="callout warning">The outcome is still unconfirmed. Your reservation is protected. Finance can inspect the existing payment; do not request a replacement.</div>' : ''}
          <div class="payout-caption">Demo bank · No real money moves</div>
        </section>
        <div class="developer-side">
          <section class="panel"><h2>Your proceeds</h2><div class="stat-row"><span>Eligible store proceeds</span><b>${money(state.pools[0].net_proceeds_cents)}</b></div><div class="stat-row"><span>Total principal advanced</span><b>${money(state.pools[0].funded_lifetime_cents)}</b></div><div class="stat-row"><span>Report covers through</span><b>${escape(state.pools[0].report_through)}</b></div><p class="muted account-note">Up to ${advanceRate}% of eligible proceeds can be advanced. Existing advances reduce what is available next.</p></section>
          <section class="panel how-payout"><h2>What happens next</h2><ol><li><b>Request your advance</b><span>Review the fee and exact amount you receive.</span></li><li><b>Receive your payout</b><span>The demo records the bank's confirmed payment.</span></li><li><b>Settle when the store pays</b><span>Store proceeds repay principal; any residual remains payable to you.</span></li></ol></section>
        </div>
      </div>
      <section class="panel payout-history"><div class="panel-title"><h2>Payout history</h2><button id="view-operations" class="text-button">View in finance →</button></div>${table(
        ['Payout', 'Status', 'Advance', 'Fee', 'Net received'],
        state.advances.map((a) => [
          escape(a.id.slice(0, 8)),
          badge(a.state, tone(a.state)),
          money(a.principal_cents),
          money(a.fee_cents),
          money(a.cash_cents),
        ]),
      )}</section>
      <div class="workspace-footer"><span>Fictional account, synthetic USD. This walkthrough demonstrates one advance from the current earnings pool.</span><button id="new-account" class="text-button" ${this.busy ? 'disabled' : ''}>Start a fresh demo account</button></div>`;
    $('#request-advance').onclick = () => this.requestAdvance();
    $('#view-operations').onclick = () => {
      this.showView('operations');
      this.refreshFinance(run.id);
    };
    $('#new-account').onclick = async () => {
      if (this.busy) return;
      this.busy = true;
      try {
        await this.newAccount();
      } catch (error) {
        this.error(error);
      } finally {
        this.busy = false;
        this.renderDeveloper();
      }
    };
  }
  async refreshFinance(id) {
    $('#ops-refresh').disabled = true;
    try {
      const list = await this.api('/api/runs');
      const current = id || this.finance?.id || this.account?.id || list[0]?.id;
      $('#ops-run').innerHTML = list
        .map(
          (item) =>
            `<option value="${escape(item.id)}">${item.purpose === 'workspace' ? 'Northstar Notes' : 'Scenario: ' + escape(item.title)} · ${item.id.slice(0, 6)}${item.archived ? ' · archived' : ''}</option>`,
        )
        .join('');
      if (!current) return;
      $('#ops-run').value = current;
      this.finance = await this.api(`/api/runs/${current}`);
      $('#ops-scope').textContent = this.finance.archived
        ? 'Read-only archive · last saved observations'
        : 'One isolated account or scenario · balances are not combined across runs';
      this.renderFinance();
    } catch (error) {
      this.error(error);
    } finally {
      $('#ops-refresh').disabled = false;
    }
  }
  renderFinance() {
    const run = this.finance;
    if (!run) return;
    const s = run.state;
    const fees = s.advances
      .filter((a) => a.state === 'SETTLED')
      .reduce((sum, a) => sum + a.fee_cents, 0);
    const exceptions = s.reconciliation.filter(
      (r) => !['MATCHED', 'NO_CASH_EXPECTED', 'PENDING'].includes(r.status),
    );
    const unknown = s.advances.filter((a) => ['UNKNOWN', 'DISPATCHING'].includes(a.state));
    const held = s.developers.some((d) => d.hold);
    const attention =
      held ||
      exceptions.length ||
      unknown.length ||
      run.collection?.status === 'UNAPPLIED' ||
      run.collection?.status === 'SHORTFALL';
    $('#operations-content').innerHTML = `
      <div class="metrics">${metric('Funding cash', money(s.bankCashCents), 'Confirmed simulated bank balance')}${metric('Cash reserved', money(s.treasury[0].reserved_cash_cents), 'Committed to pending payouts')}${metric('Fees withheld', money(fees), 'Deducted from recorded completed advances')}${metric('Needs attention', attention ? 'Review required' : 'All clear', held ? 'Risk hold active' : `${exceptions.length} reconciliation exceptions`)}</div>
      ${attention ? `<div class="attention-strip"><b>Review this account</b><span>${held ? 'New advances are held. ' : ''}${unknown.length ? 'A bank outcome is unconfirmed; keep reservations. ' : ''}${exceptions.length ? 'Payment evidence does not yet reconcile. ' : ''}${run.collection?.status === 'UNAPPLIED' ? 'A store receipt remains unapplied.' : ''}${run.collection?.status === 'SHORTFALL' ? 'Final store proceeds did not cover outstanding principal.' : ''}</span></div>` : ''}
      <section class="panel ops-records"><div class="tabs" role="tablist" aria-label="Finance records">${[
        ['payouts', 'Payouts'],
        ['risk', 'Risk & review'],
        ['ledger', 'Ledger'],
        ['reconciliation', 'Reconciliation'],
      ]
        .map(
          ([id, title]) =>
            `<button role="tab" aria-selected="${this.financeTab === id}" class="${this.financeTab === id ? 'selected' : ''}" data-finance-tab="${id}">${title}</button>`,
        )
        .join('')}</div><div id="finance-records-body"></div></section>
      <div class="workspace-footer"><span>Fees are withheld once at funding. This ledger defers them; revenue recognition is not modeled.</span><a href="/#run=${run.id}">Open engineering evidence ↗</a></div>`;
    document.querySelectorAll('[data-finance-tab]').forEach(
      (button) =>
        (button.onclick = () => {
          this.financeTab = button.dataset.financeTab;
          this.renderFinance();
        }),
    );
    const body = $('#finance-records-body');
    if (this.financeTab === 'payouts') {
      body.innerHTML = `<div class="panel-title"><h2>Payout activity</h2>${badge(`${s.advances.length} recorded payout${s.advances.length === 1 ? '' : 's'}`)}</div>${table(
        ['Reference', 'Status', 'Principal', 'Fee', 'Net cash', 'Bank reference'],
        s.advances.map((a) => [
          escape(a.id.slice(0, 8)),
          badge(a.state, tone(a.state)),
          money(a.principal_cents),
          money(a.fee_cents),
          money(a.cash_cents),
          escape(a.bank_transfer_id?.slice(0, 17) || 'Awaiting confirmation'),
        ]),
      )}<p class="muted">Opening exposure is a fixture. Fee totals cover the payouts recorded in this run, not seeded historical advances.</p>`;
    } else if (this.financeTab === 'risk') {
      body.innerHTML = `<div class="panel-title"><h2>Risk and fraud review</h2>${badge(held ? 'HOLD ACTIVE' : 'NO ACTIVE HOLD', held ? 'warn' : 'good')}</div><div class="ops-rule-summary"><span>Cancellation review threshold <b>${run.monitoringPolicy ? run.monitoringPolicy.cancellationBasisPoints / 100 : '—'}%</b></span><span>Sales velocity review threshold <b>${run.monitoringPolicy?.velocityMultiple ?? '—'}× baseline</b></span></div>${run.signals.length ? run.signals.map((signal) => `<div class="callout ${signal.severity === 'HOLD' ? 'warning' : ''}"><b>${escape(label(signal.code))}</b><br>${escape(signal.explanation)}</div>`).join('') : '<p class="muted">No signal has triggered a review. A hold can also be applied independently of these illustrative rules.</p>'}${
        run.assessment
          ? table(
              ['Check', 'Observation'],
              [
                ['Report through', escape(run.assessment.snapshot.reportThroughDate)],
                ['Expected coverage', escape(run.assessment.context.expectedReportThroughDate)],
                [
                  'Eligibility result',
                  badge(
                    run.assessment.status,
                    run.assessment.status === 'ELIGIBLE' ? 'good' : 'warn',
                  ),
                ],
                ['Current outstanding principal', money(s.developers[0].outstanding_cents)],
              ],
            )
          : ''
      }<p class="muted">Signals identify cases to review. They do not establish fraud or a borrower's creditworthiness. Missing evidence must not be described as a clean fraud verdict.</p>`;
    } else if (this.financeTab === 'ledger') {
      const entries = [...s.ledger, ...s.collectionLedger];
      const debit = entries.filter((e) => e.side === 'DEBIT').reduce((sum, e) => sum + e.cents, 0);
      const credit = entries
        .filter((e) => e.side === 'CREDIT')
        .reduce((sum, e) => sum + e.cents, 0);
      body.innerHTML = `<div class="panel-title"><h2>Ledger transactions</h2>${badge(entries.length && debit === credit ? 'BALANCED' : entries.length ? 'IMBALANCED' : 'NO POSTINGS', entries.length && debit === credit ? 'good' : 'neutral')}</div>${table(
        ['Journal', 'Account', 'Debit', 'Credit'],
        entries.map((e) => [
          escape((e.posting_key || e.receipt_id).slice(0, 22)),
          escape(label(e.account)),
          e.side === 'DEBIT' ? money(e.cents) : '—',
          e.side === 'CREDIT' ? money(e.cents) : '—',
        ]),
      )}<div class="stat-row"><span>Total debit / credit</span><b>${money(debit)} / ${money(credit)}</b></div><p class="muted">Posted entries are immutable. Opening balances are seeded controls, not a complete opening general ledger.</p>`;
    } else {
      body.innerHTML = `<h2>Reconciliation exceptions</h2>${table(
        ['Payment', 'Result', 'Expected', 'Observed'],
        s.reconciliation.map((r) => [
          escape(r.key.slice(0, 16)),
          badge(r.status, tone(r.status)),
          r.expectedCents == null ? '—' : money(r.expectedCents),
          r.observedCents == null ? '—' : money(r.observedCents),
        ]),
      )}${s.statementFaultInjected ? '<div class="callout warning">This scenario deliberately alters a statement copy by $1. The bank record and posted ledger remain unchanged.</div>' : ''}<div class="stat-row"><span>Local funding cash / bank cash</span><b>${money(s.treasury[0].cash_cents)} / ${money(s.bankCashCents)}</b></div><h3>Store collection</h3>${run.collection ? `${badge(run.collection.status, tone(run.collection.status))}<p>${escape(run.collection.explanation)}</p>${table(['Principal repaid', 'Residual payable'], [[money(run.collection.principalCents), money(run.collection.residualCents)]])}` : '<p class="muted">No final store collection has been allocated.</p>'}<p class="muted">Identity, currency, amount, and ledger evidence must agree. An unexplained difference stays open; it does not become an invented fee.</p>`;
    }
  }
}
