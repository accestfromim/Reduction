// Render the same sanitized component document as the card reader, in an isolated browser.
import puppeteer from 'puppeteer-core';
import {readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const task = JSON.parse(Buffer.concat(chunks));
const assets = fileURLToPath(new URL('../../../app/src/main/assets', import.meta.url));
const modules = new Set(['/vendor/purify.min.js', '/js/components/html/index.js', '/js/components/html/chart-table.js']);
const browser = await puppeteer.launch({executablePath:'/usr/bin/google-chrome', args:['--disable-dev-shm-usage']});
try {
  const page = await browser.newPage();
  await page.setViewport({width:1000,height:800,deviceScaleFactor:1});
  await page.setRequestInterception(true);
  page.on('request', async request => {
    const url = new URL(request.url());
    if (url.origin === 'http://card.invalid' && modules.has(url.pathname)) {
      return request.respond({contentType:'text/javascript',body:await readFile(assets + url.pathname)});
    }
    if (url.href === 'http://card.invalid/') return request.respond({contentType:'text/html',body:'<!doctype html><main></main>'});
    if (url.protocol === 'data:' || url.protocol === 'about:') return request.continue();
    return request.abort();
  });
  await page.goto('http://card.invalid/');
  await page.addScriptTag({url:'/vendor/purify.min.js'});
  const files = {};
  for (const [id,[file,meta]] of Object.entries(task.files)) {
    const href = 'data:' + meta.type + ';base64,' + (await readFile(file)).toString('base64');
    files[id] = {href, name:meta.name};
  }
  const html = await page.evaluate(async (source,files,format) => {
    const {mountHtml} = await import('/js/components/html/index.js');
    // Markdown converters put media: references in src/href; retain their identity through sanitization.
    const parsed = new DOMParser().parseFromString(source, 'text/html');
    for (const el of parsed.querySelectorAll('img[src],audio[src],video[src],a[href],source[src]')) {
      const ref = el.getAttribute('src') || el.getAttribute('href');
      if (ref?.startsWith('media:')) el.dataset.attachment = ref;
    }
    const doc = await new Promise(resolve => mountHtml(document.querySelector('main'),parsed.documentElement.outerHTML,resolve));
    const used = new Set();
    for (const el of doc.querySelectorAll('[data-attachment],[src],[href]')) {
      const key = (el.dataset.attachment || el.getAttribute('src') || el.getAttribute('href') || '').match(/^media:(.+)$/)?.[1];
      if (!key) continue;
      if (!files[key]) throw Error('缺失附件 ' + key);
      used.add(key);
      el.setAttribute(el.tagName === 'A' ? 'href' : 'src',files[key].href);
      el.removeAttribute('data-attachment');
    }
    for (const link of doc.querySelectorAll('a[data-path]')) {
      link.removeAttribute('href');
      link.title = link.dataset.path;
    }
    if (format !== 'html') {
      for (const player of doc.querySelectorAll('audio,video')) {
        const caption = player.closest('figure')?.querySelector('figcaption')?.textContent;
        const label = doc.createElement('span');
        label.textContent = caption || (player.tagName === 'VIDEO' ? '视频' : '音频');
        if (player.poster?.startsWith('data:image/')) {
          const poster = doc.createElement('img'); poster.src = player.poster; player.before(poster);
        }
        player.replaceWith(label);
      }
    }
    for (const [key,file] of Object.entries(files)) {
      if (used.has(key)) continue;
      const p = doc.createElement('p');
      const link = doc.createElement('a');
      link.textContent = file.name;
      if (format === 'html') { link.href = file.href; link.download = file.name; }
      p.append(link); doc.body.append(p);
    }
    doc.querySelectorAll('meta,script,base,link,[contenteditable]').forEach(el=>{
      if (el.hasAttribute('contenteditable')) el.removeAttribute('contenteditable'); else el.remove();
    });
    const policy = doc.createElement('meta');
    policy.httpEquiv='Content-Security-Policy';
    policy.content="default-src 'none'; img-src data:; media-src data:; style-src 'unsafe-inline'; font-src data:; form-action 'none'; base-uri 'none'";
    doc.head.prepend(policy);
    const encoding = doc.createElement('meta'); encoding.charset='utf-8'; doc.head.prepend(encoding);
    const printStyle = doc.createElement('style');
    printStyle.textContent='@media print { thead{display:table-header-group} figure,tr{break-inside:avoid} caption,h1,h2,h3{break-after:avoid} }';
    doc.head.append(printStyle);
    return '<!doctype html>\n'+doc.documentElement.outerHTML;
  },task.html,files,task.format);
  const output = name=>path.join(task.dir,name);
  if (task.format === 'html') {
    await writeFile(output('output.html'),html);
  } else {
    await page.setContent(html,{waitUntil:'load'});
    await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(im=>im.decode()))});
    if (task.format === 'pdf') {
      await page.pdf({path:output('output.pdf'),format:'A4',printBackground:true,margin:{top:'12mm',bottom:'12mm',left:'12mm',right:'12mm'}});
    } else {
      const height = await page.evaluate(()=>Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));
      const limit = 8000;
      for (let y=0, i=1;y<height;y+=limit,i++) {
        const name = height<=limit?'output.png':'part-'+String(i).padStart(6,'0')+'.png';
        await page.screenshot({path:output(name),captureBeyondViewport:true,clip:{x:0,y,width:1000,height:Math.min(limit,height-y)}});
      }
    }
  }
} finally { await browser.close(); }
