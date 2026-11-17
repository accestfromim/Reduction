import { base64Of, shrunk } from '../../core/pictures.js';
import { state } from '../../core/state.js';
import * as ui from '../../ui/ui.js';

// Pictures the user is about to send with a message: picked, photographed or pasted. They wait in a
// strip above the input, each with a way to take it off again, and go out with the next message.

const MOST = 4;
state.pictures = [];

function draw() {
  const strip = document.getElementById('pictureStrip');
  strip.innerHTML = '';
  strip.classList.toggle('hidden', !state.pictures.length);
  state.pictures.forEach((picture, index) => {
    const item = document.createElement('div');
    item.className = 'picture-thumb';
    const image = document.createElement('img');
    image.src = picture.url;
    image.alt = picture.name;
    const off = document.createElement('button');
    off.textContent = '×';
    off.setAttribute('aria-label', '去掉这张图');
    off.addEventListener('click', () => {
      state.pictures.splice(index, 1);
      draw();
    });
    item.append(image, off);
    strip.appendChild(item);
  });
  ui.updateControls();
}

/** Takes in picture files, whatever way they came. */
export async function add(files) {
  for (const file of Array.from(files || [])) {
    if (!file.type.startsWith('image/')) continue;
    if (state.pictures.length >= MOST) {
      ui.toast('一次最多发 ' + MOST + ' 张图');
      break;
    }
    try {
      const kept = await shrunk(file);
      state.pictures.push({
        name: (file.name || '图片').replace(/\.[^.]*$/, ''),
        mediaType: 'image/jpeg',
        base64: base64Of(kept.buffer),
        url: URL.createObjectURL(kept.blob)
      });
    } catch (error) {
      ui.toast('这张图读不了');
    }
  }
  draw();
}

/** The pictures waiting to go, handed over; the strip is emptied. */
export function take() {
  const going = state.pictures;
  state.pictures = [];
  draw();
  return going;
}

export function bind() {
  const input = document.getElementById('pictureInput');
  document.getElementById('pictureBtn').addEventListener('click', () => input.click());
  input.addEventListener('change', (event) => {
    const files = Array.from(event.target.files || []);
    event.target.value = '';
    add(files);
  });
  // A picture on the clipboard — a screenshot, a copied image — is pasted like text.
  ui.els.textInput.addEventListener('paste', (event) => {
    const files = Array.from((event.clipboardData && event.clipboardData.files) || []).filter((file) => file.type.startsWith('image/'));
    if (!files.length) return;
    event.preventDefault();
    add(files);
  });
}
