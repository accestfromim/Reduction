// Edit the authored text nodes; the floating controls exist only in the editing frame.
export function bindTextFormat(frame, originals, bindHandle, commit) {
  const bar = frame.createElement('div');
  bar.className = 'card-text-format';
  bar.setAttribute('role','toolbar');
  bar.setAttribute('aria-label','文字格式');
  bar.hidden = true;
  const formats = [['strong','strong,b','加粗'],['em','em,i','斜体']];
  function selected() {
    const sel = frame.getSelection();
    if (!sel.rangeCount || sel.isCollapsed) return [];
    const range = sel.getRangeAt(0), parts = [];
    for (const h of frame.querySelectorAll('[data-reduction-field]')) {
      if (!h.isConnected || !range.intersectsNode(h)) continue;
      const sub = frame.createRange();
      sub.selectNodeContents(h);
      if (h.contains(range.startContainer)) sub.setStart(range.startContainer,range.startOffset);
      if (h.contains(range.endContainer)) sub.setEnd(range.endContainer,range.endOffset);
      const text = sub.toString();
      if (!text) continue;
      const node = originals[Number(h.dataset.reductionField)];
      if (!node || node.parentElement.closest('card-private,[data-card-privacy]')) return [];
      const prefix = sub.cloneRange();
      prefix.selectNodeContents(h);
      prefix.setEnd(sub.startContainer,sub.startOffset);
      const start = prefix.toString().length;
      if (node.data.slice(start,start+text.length) !== text) return [];
      parts.push({node,handle:h,start,end:start+text.length});
    }
    return parts;
  }
  function split(node, handle, offset) {
    const tail = node.splitText(offset);
    const next = handle.cloneNode(false);
    handle.textContent = node.data;
    next.textContent = tail.data;
    next.dataset.reductionField = originals.push(tail) - 1;
    handle.after(next);
    bindHandle(next);
    return [tail, next];
  }
  function toggle(node, tag, selector, remove) {
    const mark = node.parentElement.closest(selector);
    if (!remove) {
      if (!mark) {
        const wrapper = node.ownerDocument.createElement(tag);
        node.before(wrapper);
        wrapper.append(node);
      }
      return;
    }
    // Isolate the selected branch, moving siblings intact so existing edit handles stay bound.
    let branch = node;
    while (branch !== mark) {
      const parent = branch.parentElement;
      const left = parent.cloneNode(false), right = parent.cloneNode(false);
      left.removeAttribute('id'); right.removeAttribute('id');
      while (branch.previousSibling) left.prepend(branch.previousSibling);
      while (branch.nextSibling) right.append(branch.nextSibling);
      if (left.hasChildNodes()) parent.before(left);
      if (right.hasChildNodes()) parent.after(right);
      branch = parent;
    }
    mark.replaceWith(...mark.childNodes);
  }
  function apply(tag, selector) {
    const parts = selected();
    if (!parts.length) return;
    const remove = parts.every(p => p.node.parentElement.closest(selector));
    const selection = frame.getSelection();
    const prior = selection.getRangeAt(0);
    const backwards = selection.anchorNode === prior.endContainer && selection.anchorOffset === prior.endOffset;
    const top = prior.getBoundingClientRect().top;
    const formatted = [];
    for (const part of parts) {
      let {node,handle,start,end} = part;
      if (end < node.length) split(node,handle,end);
      if (start) [node,handle] = split(node,handle,start);
      toggle(node,tag,selector,remove);
      toggle(handle,tag,selector,remove);
      formatted.push(handle);
    }
    const first = formatted[0], last = formatted[formatted.length-1];
    first.focus({preventScroll:true});
    const points = [first,0,last,last.childNodes.length];
    if (backwards) selection.setBaseAndExtent(last,last.childNodes.length,first,0);
    else selection.setBaseAndExtent(...points);
    frame.scrollingElement.scrollTop += selection.getRangeAt(0).getBoundingClientRect().top - top;
    commit();
    position();
  }
  for (const [tag,selector,label] of formats) {
    const btn=frame.createElement('button');
    btn.type='button'; btn.textContent=tag==='strong'?'B':'I'; btn.setAttribute('aria-label',label); btn.title=label;
    btn.onmousedown=e=>e.preventDefault();
    btn.onclick=()=>apply(tag,selector);
    bar.append(btn);
  }
  frame.body.append(bar);
  function position() {
    const parts = selected();
    bar.hidden=!parts.length;
    if (!parts.length) return;
    formats.forEach(([,selector],i)=>bar.children[i].setAttribute('aria-pressed',String(parts.every(p=>p.node.parentElement.closest(selector)))));
    const rect=frame.getSelection().getRangeAt(0).getBoundingClientRect();
    bar.style.left=Math.max(4,Math.min(rect.left,frame.documentElement.clientWidth-bar.offsetWidth-4))+'px';
    bar.style.top=(rect.top>bar.offsetHeight+8 ? rect.top-bar.offsetHeight-4 : Math.max(4,rect.bottom+4))+'px';
  }
  frame.addEventListener('selectionchange',position);
  frame.addEventListener('scroll',position,{passive:true});
  frame.addEventListener('keydown',e=>{
    if (!(e.ctrlKey || e.metaKey) || e.altKey || e.isComposing) return;
    const key=e.key.toLowerCase();
    if ((key==='b'||key==='i') && selected().length) {
      e.preventDefault();
      const [tag,selector]=formats[key==='b'?0:1];apply(tag,selector);
    }
  });
}
