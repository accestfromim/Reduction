import { base64Of } from './pictures.js';

// A PDF as pictures of its pages. The pages are drawn here, by pdf.js, the same on a phone and on a
// computer: what is kept of a PDF, shown in a card and given to the assistant to look at is these pictures.

const VENDOR = new URL('../../vendor/pdfjs/', import.meta.url).href;
let library = null;

async function pdfjs() {
  if (!library) {
    library = await import(VENDOR + 'pdf.min.mjs');
    library.GlobalWorkerOptions.workerSrc = VENDOR + 'pdf.worker.min.mjs';
  }
  return library;
}

/** The long side of a page's picture, in pixels: enough to read small print by. */
const LONG_SIDE = 1600;
export const MAX_PAGES = 200;

/**
 * Draws every page of the PDF in [data] (an ArrayBuffer) and hands each to [each] as
 * (page number from 1, JPEG in base64, number of pages). Returns the number of pages drawn.
 */
export async function drawPages(data, each) {
  const { getDocument } = await pdfjs();
  const pdf = await getDocument({ data, cMapUrl: VENDOR + 'cmaps/', cMapPacked: true, standardFontDataUrl: VENDOR + 'standard_fonts/' }).promise;
  const count = Math.min(pdf.numPages, MAX_PAGES);
  try {
    for (let number = 1; number <= count; number++) {
      const page = await pdf.getPage(number);
      const natural = page.getViewport({ scale: 1 });
      const viewport = page.getViewport({ scale: LONG_SIDE / Math.max(natural.width, natural.height) });
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(viewport.width);
      canvas.height = Math.round(viewport.height);
      const context = canvas.getContext('2d');
      // A page with no background of its own is paper: white.
      context.fillStyle = '#fff';
      context.fillRect(0, 0, canvas.width, canvas.height);
      await page.render({ canvasContext: context, viewport }).promise;
      const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
      await each(number, base64Of(await blob.arrayBuffer()), count);
      page.cleanup();
      canvas.width = canvas.height = 0;
    }
  } finally {
    await pdf.destroy();
  }
  return count;
}
