import { base64Of } from '../../core/pictures.js';
import { callBridge } from '../../core/bridge.js';

export function bindImagePaste(frame, source, fields, pictures, commit) {
  frame.addEventListener('paste', async event => {
    const types = {'image/png':'png','image/jpeg':'jpg','image/gif':'gif','image/webp':'webp'};
    const files = [...(event.clipboardData?.files || [])].filter(f => types[f.type]);
    if (!files.length) return;
    event.preventDefault();
    const selected = frame.activeElement?.closest('[data-card-picture]');
    const picture = selected && pictures[Number(selected.dataset.cardPicture)];
    const selection = frame.getSelection();
    const range = selection.rangeCount ? selection.getRangeAt(0) : null;
    const handle = frame.activeElement?.closest('[data-reduction-field]');
    const node = handle && fields[Number(handle.dataset.reductionField)];
    let start, end;
    if (!picture) {
      if (!node || !range || !handle.contains(range.startContainer) || !handle.contains(range.endContainer)) {
        window.alert('请把光标放在插图位置，或先选中一张图片。');
        return;
      }
      const prefix = range.cloneRange();
      prefix.selectNodeContents(handle);
      prefix.setEnd(range.startContainer,range.startOffset);
      start = prefix.toString().length;
      end = start + range.toString().length;
    }
    const snapshot = source.documentElement.outerHTML;
    try {
      const buffers = await Promise.all(files.map(file => file.arrayBuffer()));
      if (!frame.defaultView?.frameElement?.isConnected || source.documentElement.outerHTML !== snapshot) return;
      const fragment = source.createDocumentFragment();
      let lastImage;
      buffers.forEach((bytes,i) => {
        const id = callBridge('saveCardMedia',base64Of(bytes),types[files[i].type]);
        if (!id) throw Error('图片未保存，请检查大小和格式');
        lastImage = source.createElement('img');
        lastImage.dataset.attachment = 'media:' + id;
        lastImage.alt = files[i].name || '粘贴的图片';
        fragment.append(lastImage);
      });
      if (picture) {
        const figure = picture.closest('figure');
        if (figure && figure.querySelectorAll('img,svg,audio,video').length === 1) {
          const next = source.createElement('figure');
          next.append(fragment);
          figure.after(next);
        } else picture.after(fragment);
      } else {
        const insertion = source.createRange();
        insertion.setStart(node,start);
        insertion.setEnd(node,end);
        insertion.deleteContents();
        insertion.insertNode(fragment);
      }
      commit([...source.querySelectorAll('img,svg')].indexOf(lastImage));
    } catch (error) { window.alert('粘贴失败：' + error.message); }
  });
}
