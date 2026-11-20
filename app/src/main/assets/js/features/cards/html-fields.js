import { drawChart } from '../../components/html/chart-table.js';

// Keep the authored document separate from edit handles and generated chart markup.
export function htmlFields(source, commit) {
  const doc = new DOMParser().parseFromString(source, 'text/html');
  const preview = doc.cloneNode(true);
  const excluded = 'script,style,template,noscript,svg,math,iframe,object,form,a,button,select,textarea,audio,video,[hidden],[aria-hidden="true"],[contenteditable="false"]';
  function editableNodes(root) {
    if (!root.children.length && !root.textContent.trim()) root.append(root.ownerDocument.createElement('p'));
    for (const el of root.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,td,th,figcaption')) {
      if (!el.firstChild) el.append(root.ownerDocument.createTextNode(''));
    }
    const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (!node.parentElement.closest(excluded) && (node.data.trim() || node.data === '')) nodes.push(node);
    }
    return nodes;
  }
  const originals = editableNodes(doc.body);
  preview.querySelectorAll('[data-reduction-field]').forEach(el => el.removeAttribute('data-reduction-field'));
  editableNodes(preview.body).forEach((node, index) => {
    const handle = preview.createElement('span');
    handle.dataset.reductionField = index;
    node.replaceWith(handle);
    handle.append(node);
  });
  const serialize = document => '<!doctype html>\n' + document.documentElement.outerHTML;
  return {
    html: serialize(preview),
    bind(frame) {
      for (const handle of frame.querySelectorAll('[data-reduction-field]')) {
        const original = originals[Number(handle.dataset.reductionField)];
        if (!original) continue;
        handle.contentEditable = 'plaintext-only';
        handle.style.minWidth = '.25em';
        if (!original.data) handle.style.display = 'inline-block';
        const apply = () => {
          const value = handle.innerText;
          if (original.data === value) return;
          original.data = value;
          if (!value) handle.style.display = 'inline-block';
          drawChart(handle.closest('table[data-chart]'));
          commit(serialize(doc));
        };
        handle.addEventListener('input', event => { if (!event.isComposing) apply(); });
        handle.addEventListener('compositionend', apply);
      }
    }
  };
}
