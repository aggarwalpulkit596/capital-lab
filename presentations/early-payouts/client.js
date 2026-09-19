/* Reveal.js is the presentation runtime. This script adds local navigation and a teaching widget. */
const params = new URLSearchParams(location.search);
const menu = document.querySelector('#deck-menu');
const menuButton = document.querySelector('#deck-menu-button');
menuButton.addEventListener('click', () => menu.showModal());
document.querySelector('.close-menu').addEventListener('click', () => menu.close());
document.querySelector('#slide-search').addEventListener('input', (event) => {
  const query = event.target.value.toLowerCase();
  menu.querySelectorAll('li').forEach((item) => {
    item.hidden = !item.textContent.toLowerCase().includes(query);
  });
});
menu
  .querySelectorAll('[data-slide-link]')
  .forEach((link) => link.addEventListener('click', () => menu.close()));
const quoteForm = document.querySelector('#quote-lab');
function updateQuote() {
  if (!quoteForm) return;
  const values = ['proceeds', 'exposure', 'ceiling'].map((name) => quoteForm.elements[name].value);
  const output = document.querySelector('#quote-output');
  if (values.some((value) => !/^\d+$/.test(value) || BigInt(value) > 1000000n)) {
    output.textContent = 'Enter whole-dollar amounts between 0 and 1,000,000.';
    return;
  }
  const [proceeds, exposure, ceiling] = values.map((value) => BigInt(value) * 100n);
  const minimum = (a, b) => (a < b ? a : b);
  const positive = (value) => (value > 0n ? value : 0n);
  const limit = minimum((proceeds * 8000n) / 10000n, ceiling);
  const capacity = positive(limit - exposure);
  const excess = positive(exposure - limit);
  const principal = quoteForm.elements.stale.checked ? 0n : capacity;
  const fee = (principal * 250n + 5000n) / 10000n;
  const money = (amount) =>
    '$' +
    Number(amount / 100n).toLocaleString('en-US') +
    '.' +
    String(amount % 100n).padStart(2, '0');
  output.innerHTML = `<div class="numbers"><div><strong>${money(principal)}</strong><span>Actionable principal</span></div><div><strong>${money(fee)}</strong><span>Fee</span></div><div><strong>${money(principal - fee)}</strong><span>Net cash</span></div></div><p class="diagnostic">${quoteForm.elements.stale.checked ? 'HOLD: old report coverage' : principal > 0n ? 'ELIGIBLE: arithmetic quote only' : 'NO CAPACITY'}<br>Diagnostic capacity ${money(capacity)}. Excess exposure ${money(excess)}.</p>`;
}
quoteForm?.addEventListener('submit', (event) => event.preventDefault());
quoteForm?.addEventListener('input', updateQuote);
updateQuote();
window.addEventListener('beforeprint', () => {
  quoteForm?.reset();
  updateQuote();
});
Reveal.on('pdf-ready', () => {
  document.documentElement.dataset.pdfReady = 'true';
});
Reveal.initialize({
  width: 1280,
  height: 720,
  margin: 0.02,
  center: false,
  hash: true,
  controls: true,
  progress: true,
  slideNumber: 'c/t',
  transition: 'none',
  backgroundTransition: 'none',
  disableLayout: false,
  pdfSeparateFragments: false,
  pdfMaxPagesPerSlide: 1,
  showNotes: params.has('notes') ? 'separate-page' : false,
  view: params.has('scroll') ? 'scroll' : 'slide',
  plugins: [RevealNotes, RevealSearch],
  keyboard: { 77: () => (menu.open ? menu.close() : menu.showModal()) },
}).then(() => {
  document.documentElement.dataset.deckReady = 'true';
});
