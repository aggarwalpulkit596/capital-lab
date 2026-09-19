import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { slides, sources } from '../presentations/early-payouts/deck.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'build/presentations/early-payouts');
const input = path.join(root, 'presentations/early-payouts');
await fs.mkdir(output, { recursive: true });
const esc = (text) =>
  String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
const ids = new Set();
for (const slide of slides) {
  if (ids.has(slide.id)) throw new Error(`Duplicate slide ${slide.id}`);
  ids.add(slide.id);
  if (!slide.notes || !slide.refs.every((ref) => sources[ref]))
    throw new Error(`Missing notes/source for ${slide.id}`);
}
const sourceIds = Object.fromEntries(
  Object.keys(sources).map((key, index) => [key, `S${String(index + 1).padStart(2, '0')}`]),
);
const darkIds = new Set([
  'cover',
  'cash-cycle',
  'underwriting',
  'hld',
  'lost-response',
  'ledger-primer',
  'validation',
  'roadmap',
]);
function documentFor(selected, short) {
  const content = selected
    .map((slide, index) => {
      const footer = slide.refs
        .map(
          (key) =>
            `<a href="${sources[key][1]}" target="_blank" rel="noopener" title="${esc(sources[key][0])}">${sourceIds[key]}</a>`,
        )
        .join('');
      const notes = `<p>${esc(slide.notes)}</p><p>Sources, checked 19 September 2026:</p><ul>${slide.refs.map((key) => `<li><a href="${sources[key][1]}">${esc(sources[key][0])}</a></li>`).join('')}</ul>`;
      return `<section id="${slide.id}" ${darkIds.has(slide.id) ? 'class="dark" data-background-color="#0b2534"' : ''}><p class="eyebrow">${esc(slide.chapter)}</p><h2>${slide.title}</h2><div class="slide-body">${slide.body}</div><footer class="slide-footer"><span>${esc(slide.scope)} · ${String(index + 1).padStart(2, '0')} / ${selected.length}</span><span>Sources ${footer}</span></footer><aside class="notes">${notes}</aside></section>`;
    })
    .join('\n');
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>RC Capital & Early Payouts${short ? ' — Interview route' : ''}</title><meta name="description" content="Independent learning deck: financial concepts, Kotlin reference implementation, and future architecture. Verified public sources and speaker notes."><link rel="stylesheet" href="vendor/reveal.css"><link rel="stylesheet" href="theme.css"></head><body><button id="deck-menu-button" aria-haspopup="dialog">Contents / M</button><dialog id="deck-menu" aria-labelledby="menu-title"><button class="close-menu" aria-label="Close contents">Close</button><h1 id="menu-title">RC Capital & Early Payouts</h1><nav><a href="index.html">Full learning deck (${slides.length})</a><a href="interview.html">Interview route (${slides.filter((s) => s.interview).length})</a><a href="${short ? 'interview' : 'index'}.html?scroll">Scroll view</a><a href="${short ? 'interview' : 'index'}.html?print-pdf">Print / PDF</a><a href="${short ? 'interview' : 'index'}.html?print-pdf&notes">PDF with notes</a><a href="sources.html">Sources</a></nav><p class="help">Arrow keys move through slides. S opens speaker notes. Esc shows the overview. M opens this menu. Financial calculations use synthetic data.</p><label for="slide-search">Find a concept</label><input id="slide-search" type="search" placeholder="Search by topic or chapter"><ol>${selected.map((s) => `<li><a data-slide-link href="#/${s.id}">${s.title.replaceAll('<br>', ' ')} <small>(${esc(s.chapter)})</small></a></li>`).join('')}</ol></dialog><div class="reveal"><div class="slides">${content}</div></div><script src="vendor/reveal.js"></script><script src="vendor/notes.js"></script><script src="vendor/search.js"></script><script src="client.js"></script></body></html>`;
}
await fs.writeFile(path.join(output, 'index.html'), documentFor(slides, false));
await fs.writeFile(
  path.join(output, 'interview.html'),
  documentFor(
    slides.filter((s) => s.interview),
    true,
  ),
);
for (const file of ['theme.css', 'client.js', 'README.md'])
  await fs.copyFile(path.join(input, file), path.join(output, file));
await fs.mkdir(path.join(output, 'vendor'), { recursive: true });
const pkg = path.join(root, 'node_modules/reveal.js');
for (const [from, to] of [
  ['dist/reveal.js', 'reveal.js'],
  ['dist/reveal.css', 'reveal.css'],
  ['dist/plugin/notes.js', 'notes.js'],
  ['dist/plugin/search.js', 'search.js'],
  ['LICENSE', 'LICENSE-reveal.js'],
]) {
  await fs.copyFile(path.join(pkg, from), path.join(output, 'vendor', to));
}
await fs.writeFile(
  path.join(output, 'sources.html'),
  `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Sources — RC Capital learning deck</title><style>body{max-width:900px;margin:50px auto;padding:20px;font:18px/1.6 Arial;color:#122c3a}a{color:#147a68}li{margin:15px 0}</style><h1>Sources and scope</h1><p>Checked 19 September 2026. This independent deck distinguishes public facts, Capital Lab implementation, and future proposals. The implementation snapshot is 97a40ca.</p><ol>${Object.entries(
    sources,
  )
    .map(
      ([key, [name, url]]) =>
        `<li id="${sourceIds[key]}"><b>${sourceIds[key]}</b> <a href="${url}">${esc(name)}</a></li>`,
    )
    .join(
      '',
    )}</ol><p>Original content: Apache 2.0. Reveal.js 6.0.2: MIT. Public retail data: CC BY 4.0, with attribution in the project.</p><a href="index.html">Back to deck</a></html>`,
);
await fs.writeFile(
  path.join(output, 'speaker-notes.md'),
  '# RC Capital & Early Payouts — speaker notes\n\nChecked 19 September 2026. Implementation snapshot: 97a40ca.\n\n' +
    slides
      .map(
        (s, i) =>
          `## ${i + 1}. ${s.title.replaceAll('&amp;', '&').replaceAll('<br>', ' ')}\n\n${s.chapter}. ${s.scope}.\n\n${s.notes}\n\n${s.refs.map((key) => `- [${sources[key][0]}](${sources[key][1]})`).join('\n')}\n`,
      )
      .join('\n'),
);
await fs.copyFile(path.join(root, 'LICENSE'), path.join(output, 'LICENSE'));
await fs.writeFile(
  path.join(output, 'manifest.json'),
  JSON.stringify(
    {
      title: 'RC Capital & Early Payouts',
      checkedAt: '2026-09-19',
      implementationCommit: '97a40ca',
      revealVersion: '6.0.2',
      slideCount: slides.length,
      interviewSlideCount: slides.filter((s) => s.interview).length,
      slides: slides.map((s) => ({ id: s.id, title: s.title, chapter: s.chapter, scope: s.scope })),
    },
    null,
    2,
  ),
);
console.log(
  `Built ${slides.length} learning slides and ${slides.filter((s) => s.interview).length} interview slides in ${output}`,
);
