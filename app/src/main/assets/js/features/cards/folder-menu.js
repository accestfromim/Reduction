// Read direct children from card paths. A path may be both a card and a folder.
export function chooseCard(anchor, start, names, open) {
  document.querySelector('.card-folder-menu')?.close();
  const menu = document.createElement('dialog');
  menu.className = 'card-folder-menu';
  const position = anchor.getBoundingClientRect();
  function show(folder) {
    menu.replaceChildren();
    menu.setAttribute('aria-label', folder || '全部卡片');
    function item(label, action) {
      const button = document.createElement('button');
      button.textContent = label;
      button.onclick = action;
      menu.append(button);
    }
    if (folder) item('‹ ' + folder.split('/').pop(), () => show(folder.split('/').slice(0,-1).join('/')));
    const prefix = folder ? folder + '/' : '';
    const folders = new Set(), cards = [];
    for (const name of names) {
      if (!name.startsWith(prefix)) continue;
      const child = name.slice(prefix.length);
      if (child.includes('/')) folders.add(child.split('/')[0]);
      else if (child) cards.push(child);
    }
    for (const dir of [...folders].sort()) item(dir + ' ›', () => show(prefix + dir));
    for (const card of cards.sort()) item(card, () => { menu.close(); open(prefix + card); });
    menu.scrollTop = 0;
    menu.querySelector('button')?.focus({preventScroll:true});
  }
  menu.addEventListener('close', () => menu.remove(), {once:true});
  menu.onclick = e => {
    const r = menu.getBoundingClientRect();
    if (e.target === menu && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom)) menu.close();
  };
  document.body.append(menu);
  menu.style.top = Math.round(position.bottom) + 'px';
  menu.style.left = Math.max(4,Math.min(position.left, innerWidth - Math.min(280,innerWidth-8) - 4)) + 'px';
  show(start);
  menu.showModal();
}
