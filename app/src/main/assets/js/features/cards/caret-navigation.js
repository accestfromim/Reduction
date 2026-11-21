// Browser arrows work within one plaintext editing host. At its edge, continue in document order.
export function bindCaretNavigation(doc) {
  const stops = '[data-reduction-field][contenteditable], [data-card-picture]';
  doc.addEventListener('keydown', event => {
    if (!['ArrowLeft','ArrowRight'].includes(event.key) || event.isComposing ||
        event.shiftKey || event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented) return;
    const current = event.target.closest?.(stops);
    if (!current) return;
    const forward = event.key === 'ArrowRight';
    const selection = doc.getSelection();
    if (!current.hasAttribute('data-card-picture')) {
      if (!selection.isCollapsed || !selection.rangeCount || !current.contains(selection.focusNode)) return;
      const remaining = doc.createRange();
      remaining.selectNodeContents(current);
      if (forward) remaining.setStart(selection.focusNode,selection.focusOffset);
      else remaining.setEnd(selection.focusNode,selection.focusOffset);
      if (remaining.toString() || remaining.cloneContents().querySelector('br')) return;
    }
    const items = [...doc.querySelectorAll(stops)].filter(el=>el.getClientRects().length);
    const next = items[items.indexOf(current) + (forward ? 1 : -1)];
    event.preventDefault();
    if (!next) return;
    if (next.hasAttribute('data-card-picture')) {
      next.dispatchEvent(new Event('card-select-picture'));
    } else {
      next.focus({preventScroll:true});
      const caret = doc.createRange();
      caret.selectNodeContents(next);
      caret.collapse(forward);
      selection.removeAllRanges();
      selection.addRange(caret);
    }
    const box = next.hasAttribute('data-card-picture') ? next.getBoundingClientRect() :
      selection.getRangeAt(0).getBoundingClientRect();
    if (box.height && (box.bottom < 0 || box.top > doc.documentElement.clientHeight)) {
      doc.defaultView.scrollBy(0,box.top - doc.documentElement.clientHeight/2);
    }
  },true);
}
