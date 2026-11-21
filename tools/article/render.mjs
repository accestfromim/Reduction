#!/usr/bin/env node
// Renders an article to the long picture it is published as.
//
//   node tools/article/render.mjs <article.md|.html> [-o out.png] [--media <dir>] [--html out.html]
//
// It puts together the page that builds the article by itself — article/builder.html with the libraries,
// the build script, the template, the stylesheets and the article inside — and has
// harness/render-article.sh draw it with this computer's Chrome. That is the very page and the very
// script the assistant's render_article tool uses, and the build script is the one the app's preview
// runs: one way of setting an article, whoever asks.
//
// --media names the folder the article's attached pictures ("media:<id>") are read from.
// --html also keeps the builder page: it stands on its own and opens in any browser.
// The template is the bundled one; --template and --style name others.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets');
const args = process.argv.slice(2);
const take = (flag) => { const at = args.indexOf(flag); return at < 0 ? '' : args.splice(at, 2)[1]; };
const out = take('-o'), mediaDir = take('--media'), htmlOut = take('--html'), templateFile = take('--template'), styleFile = take('--style');
const source = args[0];
if (!source) { console.error('usage: render.mjs <article.md|.html> [-o out.png] [--media <dir>] [--html out.html] [--template t.html] [--style s.css]'); process.exit(2); }
const target = path.resolve(out || source.replace(/\.[^.]*$/, '') + '.png');
const asset = (name) => fs.readFileSync(path.join(assets, name), 'utf-8');
let text = fs.readFileSync(source, 'utf-8');
if (/data-card-privacy|<\/?card-private/i.test(text)) {
  const {project} = await import('data:text/javascript;base64,' + Buffer.from(asset('article/privacy.js')).toString('base64'));
  text = project(text, JSON.parse(asset('article/privacy.json')));
}

// The same filling-in the app does (feature/article/ArticleBuilder.kt).
const script = (code) => code.replace(/<\/script/gi, '<\\/script');
const carried = (value) => Buffer.from(value, 'utf-8').toString('base64');
const katexStyle = asset('vendor/katex/katex.min.css').replace(/url\(([^)]+)\)/g, (_, file) =>
  file.endsWith('.woff2') ? 'url(data:font/woff2;base64,' + fs.readFileSync(path.join(assets, 'vendor/katex', file)).toString('base64') + ')' : 'url(data:,)');
const media = {};
const missing = [];
for (const [, id] of text.matchAll(/media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})/g)) {
  const kind = id.split('.').pop();
  if (!/^(jpg|jpeg|png|webp|gif|svg)$/.test(kind) || media[id]) continue;
  const file = mediaDir ? path.join(mediaDir, id) : '';
  if (file && fs.existsSync(file)) media[id] = 'data:image/' + (kind === 'jpg' ? 'jpeg' : kind === 'svg' ? 'svg+xml' : kind) + ';base64,' + fs.readFileSync(file).toString('base64');
  else missing.push(id);
}
let page = asset('article/builder.html');
for (const [place, value] of [
  ['[[libraries]]', script(['vendor/marked.umd.js', 'vendor/purify.min.js', 'vendor/katex/katex.min.js'].map(asset).join('\n;\n'))],
  ['[[build]]', script(asset('article/build.js'))],
  ['[[template]]', carried(templateFile ? fs.readFileSync(templateFile, 'utf-8') : asset('article/template.html'))],
  ['[[style]]', carried(styleFile ? fs.readFileSync(styleFile, 'utf-8') : asset('article/article.css'))],
  ['[[katex-style]]', carried(katexStyle)],
  ['[[data]]', carried(JSON.stringify({ source: text, media }))]
]) {
  const at = page.lastIndexOf(place);
  if (at < 0) throw new Error('builder.html has no ' + place);
  page = page.slice(0, at) + value + page.slice(at + place.length);
}
if (htmlOut) fs.writeFileSync(htmlOut, page);

const name = path.basename(target).replace(/\.png$/i, '');
const made = spawnSync('sh', [path.join(assets, 'harness/render-article.sh'), name], { input: page, encoding: 'utf-8', maxBuffer: 64 * 1024 * 1024 });
const said = (made.stdout || '').split('\n=====IMAGE')[0];
const kept = (said.match(/^PATH (.+)$/m) || [])[1];
const size = said.match(/^SIZE (\d+) (\d+)$/m);
if (!kept || !size) { console.error(said.trim() || made.stderr || '出图失败'); process.exit(1); }
if (path.resolve(kept) !== target) fs.copyFileSync(kept, target);
if (missing.length) console.error('这些图片没有取到，图上是空的：' + missing.join('、'));
console.log(`${target}: ${size[1]} x ${size[2]}`);
