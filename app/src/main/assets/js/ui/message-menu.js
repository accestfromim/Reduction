export function createMessageMenu({
  elements,
  copyText,
  showToast,
  readText,
  vibrate
}) {
  let pressedBubble = null;
  let pressedTimer = null;
  let pressedX = 0;
  let pressedY = 0;
  let pressedPointerId = null;
  let menuOpen = false;
  let menuPointerId = null;
  let menuActiveAction = null;
  let menuContent = '';
  let menuCanRead = false;

  function setActive(action) {
    if (menuActiveAction === action) return;
    menuActiveAction = action;
    elements.copy.classList.toggle('active', action === 'copy');
    elements.read.classList.toggle('active', action === 'read');
  }

  function actionAtPoint(x, y) {
    const target = document.elementFromPoint(x, y);
    const button = target && target.closest ? target.closest('.message-action') : null;
    if (button === elements.copy) return 'copy';
    if (button === elements.read) return 'read';
    return null;
  }

  function onPointerMove(event) {
    if (!menuOpen) return;
    if (menuPointerId != null && event.pointerId !== menuPointerId) return;
    event.preventDefault();
    setActive(actionAtPoint(event.clientX, event.clientY));
  }

  function onPointerUp(event) {
    if (!menuOpen) return;
    if (menuPointerId != null && event.pointerId !== menuPointerId) return;
    const action = menuActiveAction;
    detachGesture();
    if (action) {
      runAction(action);
    } else {
      setActive(null);
    }
  }

  function onPointerCancel(event) {
    if (!menuOpen) return;
    if (menuPointerId != null && event.pointerId !== menuPointerId) return;
    detachGesture();
    setActive(null);
  }

  function preventTouch(event) {
    if (menuOpen) event.preventDefault();
  }

  function detachGesture() {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerCancel);
    document.removeEventListener('touchmove', preventTouch);
    menuPointerId = null;
  }

  function runAction(action) {
    const content = menuContent;
    const canRead = menuCanRead;
    close();
    if (!content) return;
    if (action === 'copy') {
      copyText(content);
      showToast('已复制');
    } else if (action === 'read' && canRead) {
      readText(content);
    }
  }

  function open(message, x, y, pointerId) {
    close();
    menuContent = message.content;
    menuCanRead = message.role === 'assistant';
    elements.read.classList.toggle('hidden', !menuCanRead);
    elements.backdrop.classList.remove('hidden');

    const menu = elements.menu;
    menu.classList.remove('hidden');
    menu.style.visibility = 'hidden';
    menu.style.left = '0px';
    menu.style.top = '0px';

    const rect = menu.getBoundingClientRect();
    const margin = 10;
    const gap = 14;
    let left = x - rect.width / 2;
    left = Math.max(margin, Math.min(left, window.innerWidth - rect.width - margin));
    let top = y - rect.height - gap;
    if (top < margin) top = y + gap;
    top = Math.max(margin, Math.min(top, window.innerHeight - rect.height - margin));

    menu.style.left = Math.round(left) + 'px';
    menu.style.top = Math.round(top) + 'px';
    menu.style.visibility = 'visible';

    menuOpen = true;
    menuPointerId = pointerId;
    setActive(null);
    window.addEventListener('pointermove', onPointerMove, { passive: false });
    window.addEventListener('pointerup', onPointerUp);
    window.addEventListener('pointercancel', onPointerCancel);
    document.addEventListener('touchmove', preventTouch, { passive: false });
    vibrate(15);
  }

  function close() {
    detachGesture();
    menuOpen = false;
    menuActiveAction = null;
    menuContent = '';
    menuCanRead = false;
    elements.backdrop.classList.add('hidden');
    elements.menu.classList.add('hidden');
    elements.menu.style.visibility = '';
    elements.copy.classList.remove('active');
    elements.read.classList.remove('active');
  }

  function bind(bubble, message) {
    bubble.addEventListener('pointerdown', (event) => {
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      pressedBubble = bubble;
      pressedX = event.clientX;
      pressedY = event.clientY;
      pressedPointerId = event.pointerId;
      clearTimeout(pressedTimer);
      pressedTimer = setTimeout(() => {
        if (pressedBubble === bubble) {
          open(message, pressedX, pressedY, pressedPointerId);
        }
      }, 320);
    });
    bubble.addEventListener('pointermove', (event) => {
      if (!pressedBubble) return;
      if (Math.abs(event.clientX - pressedX) > 10 || Math.abs(event.clientY - pressedY) > 10) {
        clearTimeout(pressedTimer);
        pressedBubble = null;
      }
    });
    const release = () => {
      clearTimeout(pressedTimer);
      pressedBubble = null;
    };
    bubble.addEventListener('pointerup', release);
    bubble.addEventListener('pointercancel', release);
    bubble.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      open(message, event.clientX, event.clientY, null);
    });
  }

  elements.backdrop.addEventListener('click', close);
  elements.copy.addEventListener('click', () => {
    if (menuOpen) runAction('copy');
  });
  elements.read.addEventListener('click', () => {
    if (menuOpen) runAction('read');
  });

  return { bind, close };
}
