import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'build/presentations/early-payouts');
const output = path.join(root, 'output/presentations');
const review = path.join(root, 'build/slide-review');
await fs.mkdir(output, { recursive: true });
await fs.mkdir(review, { recursive: true });
const mime = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
};
const server = http.createServer(async (req, res) => {
  try {
    const file = path.resolve(
      source,
      '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname),
    );
    if (!file.startsWith(source + path.sep)) {
      res.writeHead(403).end();
      return;
    }
    const content = await fs.readFile(file);
    res
      .writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream' })
      .end(content);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const failures = [];
let browser;
try {
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width: 1600, height: 900 },
    deviceScaleFactor: 1,
  });
  page.on('pageerror', (error) => failures.push(error.message));
  const manifest = JSON.parse(await fs.readFile(path.join(source, 'manifest.json'), 'utf8'));
  await page.goto(`${origin}/index.html`);
  await page.waitForFunction(() => document.documentElement.dataset.deckReady === 'true');
  for (let i = 0; i < manifest.slideCount; i++) {
    await page.evaluate((index) => Reveal.slide(index), i);
    await page.evaluate(() => document.fonts.ready);
    const problems = await page.evaluate(() => {
      const s = Reveal.getCurrentSlide();
      const bound = s.getBoundingClientRect();
      const footer = s.querySelector('footer').getBoundingClientRect();
      const body = s.querySelector('.slide-body').getBoundingClientRect();
      const errors = [];
      if (body.bottom > footer.top - 8) errors.push('Body overlaps footer');
      for (const element of s.querySelectorAll(
        'h2,.eyebrow,.slide-body p,.slide-body li,td,th,pre,strong,.diagnostic',
      )) {
        const b = element.getBoundingClientRect();
        if (b.left < bound.left - 1 || b.right > bound.right + 1 || b.bottom > bound.bottom + 1)
          errors.push('Out of bounds: ' + element.textContent.slice(0, 70));
        if (element.scrollWidth > element.clientWidth + 2)
          errors.push('Horizontal overflow: ' + element.textContent.slice(0, 70));
      }
      return errors;
    });
    failures.push(...problems.map((problem) => `${i + 1} ${manifest.slides[i].id}: ${problem}`));
    await page
      .locator('.reveal')
      .screenshot({ path: path.join(review, `slide-${String(i + 1).padStart(2, '0')}.png`) });
  }
  // Exercise the independent teaching widget, not the financial service.
  await page.evaluate(() =>
    Reveal.slide(Reveal.getSlides().findIndex((s) => s.id === 'interactive-quote')),
  );
  const initial = await page.locator('#quote-output').innerText();
  if (!initial.includes('$200.00') || !initial.includes('$5.00') || !initial.includes('$195.00'))
    failures.push('Default quote amounts are incorrect');
  await page.locator('[name=proceeds]').fill('700');
  if (!(await page.locator('#quote-output').innerText()).includes('Excess exposure $40.00'))
    failures.push('Refund quote is incorrect');
  await page.locator('[name=proceeds]').fill('1000');
  await page.locator('[name=stale]').check();
  const held = await page.locator('#quote-output').innerText();
  if (!held.includes('HOLD') || !held.includes('Diagnostic capacity $200.00'))
    failures.push('Stale quote is incorrect');
  await page.locator('#deck-menu-button').click();
  await page.locator('#slide-search').fill('ledger');
  if (!(await page.locator('#deck-menu li:visible').count()))
    failures.push('Chapter search failed');
  await page.locator('.close-menu').click();
  for (const [file, expected, name] of [
    ['index.html', manifest.slideCount, 'rc-capital-learning'],
    ['interview.html', manifest.interviewSlideCount, 'rc-capital-interview'],
  ]) {
    await page.goto(`${origin}/${file}?print-pdf`);
    await page.waitForFunction(() => document.documentElement.dataset.deckReady === 'true');
    await page.waitForFunction(() => document.documentElement.dataset.pdfReady === 'true');
    await page.evaluate(() => document.fonts.ready);
    const pages = await page.locator('.pdf-page').count();
    if (pages !== expected)
      failures.push(`${file}: expected ${expected} print pages, found ${pages}`);
    await page.pdf({
      path: path.join(output, `${name}.pdf`),
      printBackground: true,
      preferCSSPageSize: true,
    });
  }
  await fs.writeFile(
    path.join(review, 'validation.json'),
    JSON.stringify(
      {
        slideCount: manifest.slideCount,
        interviewSlideCount: manifest.interviewSlideCount,
        failures,
      },
      null,
      2,
    ),
  );
  if (failures.length) throw new Error(failures.join('\n'));
  console.log(
    `Rendered ${manifest.slideCount} slides. Widget, navigation, layout, and PDF page checks passed.`,
  );
} finally {
  await browser?.close();
  server.close();
}
