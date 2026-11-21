// Bind only authored pictures. Generated charts and PDF page thumbnails have no source handle.
export function preparePictures(preview) {
  const selector = 'img, svg';
  preview.querySelectorAll('[data-card-picture]').forEach(el => el.removeAttribute('data-card-picture'));
  preview.body.querySelectorAll(selector).forEach((el,i) => el.dataset.cardPicture = i);
}

function unit(image) {
  const figure = image.closest('figure');
  return figure && figure.querySelectorAll('img,svg,audio,video').length === 1 ? figure : image;
}

function addCaption(image) {
  let figure = unit(image);
  if (figure === image) {
    const doc = image.ownerDocument;
    figure = doc.createElement('figure');
    const paragraph = image.closest('p');
    if (paragraph) {
      const range = doc.createRange();
      range.selectNodeContents(paragraph);
      range.setStartAfter(image);
      const tail = paragraph.cloneNode(false);
      tail.removeAttribute('id');
      tail.append(range.extractContents());
      paragraph.after(figure);
      if (tail.textContent.trim() || tail.querySelector('img,svg,br')) figure.after(tail);
      figure.append(image);
      if (!paragraph.textContent.trim() && !paragraph.querySelector('img,svg,br')) paragraph.remove();
    } else {
      image.before(figure);
      figure.append(image);
    }
  }
  const caption = figure.ownerDocument.createElement('figcaption');
  figure.append(caption);
  return caption;
}

async function pngOf(el) {
  let image = el, url;
  try {
    if (el.localName === 'svg') {
      const svg = el.cloneNode(true);
      svg.setAttribute('xmlns','http://www.w3.org/2000/svg');
      const box = el.getBoundingClientRect();
      svg.setAttribute('width',Math.max(1,Math.round(box.width)));
      svg.setAttribute('height',Math.max(1,Math.round(box.height)));
      url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(svg)],{type:'image/svg+xml'}));
      image = new Image(); image.src = url;
    }
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
    canvas.getContext('2d').drawImage(image,0,0);
    return await new Promise((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(Error('图片编码失败')),'image/png'));
  } finally { if(url) URL.revokeObjectURL(url); }
}

export function bindPictures(frame, source, changed, undo) {
  const originals = [...source.body.querySelectorAll('img,svg')];
  let selected, hint;
  function clear() {
    selected?.classList.remove('card-picture-selected');
    selected = null; hint?.remove(); hint = null;
  }
  function remove(el, original) {
    unit(original).remove(); unit(el).remove(); clear(); changed();
  }
  frame.addEventListener('focusin', e => {
    if (selected && e.target.closest('[data-reduction-field]')) clear();
  });
  frame.addEventListener('card-select-picture', e => select(e.target), true);
  frame.addEventListener('click', event => {
    if (!hint?.contains(event.target)) select(event.target.closest('[data-card-picture]'));
  });
  function select(el) {
    clear();
    if (!el) return;
    const original = originals[Number(el.dataset.cardPicture)];
    if (!original) return;
    selected = el;
    frame.getSelection()?.removeAllRanges();
    el.setAttribute('tabindex','-1'); el.focus({preventScroll:true});
    el.classList.add('card-picture-selected');
    if (unit(original).querySelector(':scope > figcaption')) return;
    hint = frame.createElement('button'); hint.className='card-caption-add'; hint.textContent='添加图注';
    unit(el).after(hint);
    hint.onclick = e => {
      e.stopPropagation(); clear();
      const label = addCaption(el);
      label.contentEditable='plaintext-only'; label.setAttribute('aria-label','图注'); label.focus();
      let saved;
      const apply = () => {
        if (!saved && !label.textContent) return;
        saved ||= addCaption(original);
        saved.textContent = label.innerText; changed();
      };
      label.addEventListener('input', e => { if(!e.isComposing) apply(); });
      label.addEventListener('compositionend', apply);
      label.addEventListener('blur',()=>{if(!saved) label.remove();});
    };
  }
  frame.addEventListener('keydown', async event => {
    const el = selected;
    if (!el || frame.activeElement !== el || event.isComposing) return;
    const key = event.key.toLowerCase(), mod = event.ctrlKey || event.metaKey;
    const original = originals[Number(el.dataset.cardPicture)];
    if (mod && key === 'z') { event.preventDefault(); undo(); return; }
    if (key === 'escape') { clear(); return; }
    if (key === 'delete' || key === 'backspace') { event.preventDefault(); remove(el, original); return; }
    if (!mod || !['c','x'].includes(key)) return;
    event.preventDefault();
    try {
      await navigator.clipboard.write([new ClipboardItem({'image/png':pngOf(el)})]);
      if (key === 'x' && selected === el && el.isConnected) remove(el,original);
    } catch { window.alert('复制图片失败，请检查剪贴板权限。'); }
  });
  frame.addEventListener('keydown',event=>{
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase()==='z' && !event.defaultPrevented && !frame.activeElement?.isContentEditable) {
      event.preventDefault(); undo();
    }
  });
}
