// Writes the README's badges to docs/badges/*.svg. Local files rather than a badge service:
// the README then renders with nothing fetched from elsewhere, and matches the app's black
// and white.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.resolve(here, '../../docs/badges');
fs.mkdirSync(out, { recursive: true });

const BADGES = [
  ['android', 'Android 8.0+'],
  ['arm64', 'arm64'],
  ['windows', 'Windows + WSL'],
  ['sherpa-onnx', 'sherpa-onnx'],
  ['deepseek', 'DeepSeek'],
  ['mit', 'MIT'],
];

const HEIGHT = 24;
const FONT = 11.5;
const PAD = 10;

// Width of a run of Latin text at the badge's font size; enough to fit, with the border.
const widthOf = (text) => Math.ceil([...text].reduce((sum, c) => sum + (/[A-Z0-9+]/.test(c) ? 7.4 : 6.5), 0));

for (const [file, label] of BADGES) {
  const w = widthOf(label) + PAD * 2;
  // textLength holds the text to exactly the space measured for it, whatever font the viewer has.
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${HEIGHT}" role="img" aria-label="${label}">
  <rect x="0.5" y="0.5" width="${w - 1}" height="${HEIGHT - 1}" rx="5" fill="#0B0B0B" stroke="#3A3A3A"/>
  <text x="${w / 2}" y="16.2" fill="#F2F2F2" font-family="ui-sans-serif, -apple-system, Segoe UI, Helvetica, Arial, sans-serif"
        font-size="${FONT}" text-anchor="middle" textLength="${widthOf(label)}"
        lengthAdjust="spacingAndGlyphs">${label}</text>
</svg>
`;
  fs.writeFileSync(path.join(out, `${file}.svg`), svg);
  console.log(`docs/badges/${file}.svg  ${w}x${HEIGHT}`);
}
