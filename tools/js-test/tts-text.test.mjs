import assert from 'node:assert/strict';
import { TtsTextExtractor, toSpeechText } from '../../app/src/main/assets/js/tts-text.js';

assert.equal(
  toSpeechText('# 标题\n\n你好 **世界**，这是 [链接](https://example.com)。'),
  '标题 你好 世界，这是 链接。'
);

assert.equal(toSpeechText('$E=mc^2$'), '');
assert.equal(toSpeechText('前文 $$\\frac{1}{2}$$ 后文'), '前文 后文');
assert.equal(toSpeechText('前文\n$$\nE=mc^2\n$$\n后文'), '前文 后文');
assert.equal(toSpeechText('前文 \\(a+b\\) 后文'), '前文 后文');
assert.equal(toSpeechText('前文 \\[a+b\\] 后文'), '前文 后文');
assert.equal(toSpeechText('```js\nconsole.log(1)\n```\n正文'), '正文');
assert.equal(toSpeechText('这是 `code` 文本'), '这是 文本');
assert.equal(toSpeechText('$5 美元'), '$5 美元');
assert.equal(toSpeechText('未闭合 $x+1'), '未闭合');

const streaming = new TtsTextExtractor();
assert.equal(streaming.push('$E='), '');
assert.equal(streaming.push('mc^2$ 你好。'), ' 你好。');
assert.equal(streaming.finish(), '');

console.log('tts-text tests passed');
