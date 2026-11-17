// Sets an article in the article template. Plain script, no imports: it is loaded by the app's page and
// put whole into article/builder.html, so that the app's preview, the publishing command and the
// assistant's tool all set an article with these very lines.
//
//   ArticleBuild.page(source, { template, style, katexStyle, media })  ->  the finished page, as HTML
//
// It needs window.marked, window.DOMPurify and, for formulas, window.katex. The source is read and
// nothing more: rendering never writes to it.
(function (root) {
  var WIDTH = 540, SCALE = 2;

  function escape(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /** Formulas are set by KaTeX before the Markdown is read, so that their signs are not taken for Markdown. */
  function withMath(source) {
    var kept = [];
    if (!root.katex) return { text: source, restore: function (html) { return html; } };
    function keep(tex, display) {
      var html;
      try { html = root.katex.renderToString(tex.trim(), { displayMode: display, throwOnError: false, output: 'html' }); }
      catch (_) { html = escape(tex); }
      kept.push(html);
      return (display ? '\n\n' : '') + '\u0000M' + (kept.length - 1) + '\u0000' + (display ? '\n\n' : '');
    }
    var text = source
      .replace(/\$\$([\s\S]+?)\$\$/g, function (_, tex) { return keep(tex, true); })
      .replace(/(^|[^\\$\w])\$([^\s$][^$\n]*?[^\s$\\]|[^\s$])\$(?![\w$])/g, function (_, before, tex) { return before + keep(tex, false); });
    return { text: text, restore: function (html) { return html.replace(/(?:<p>)?\u0000M(\d+)\u0000(?:<\/p>)?/g, function (whole, index) { return kept[Number(index)]; }); } };
  }

  /**
   * The source as a title and a body of HTML. The title is the first "# " heading, taken out of the body.
   * Source that begins with a tag is HTML and is used as it is, cleaned; anything else is Markdown.
   * [media] turns the id of something attached to the card into an address the page can show it from.
   */
  function parts(source, media) {
    var text = String(source || '').replace(/\r\n/g, '\n').trim();
    var title = '';
    var heading = text.match(/^#\s+(.+?)\s*#*\s*(?:\n|$)/);
    if (heading) { title = heading[1].trim(); text = text.slice(heading[0].length).trim(); }
    var html;
    if (/^<[a-zA-Z!]/.test(text)) {
      html = text;
    } else {
      var math = withMath(text);
      html = math.restore(root.marked.parse(math.text, { gfm: true, breaks: false }));
    }
    // What is attached to the card is named "media:<id>": told apart before the cleaning, which lets
    // through only addresses of kinds it knows.
    var loose = new DOMParser().parseFromString('<body>' + html + '</body>', 'text/html');
    Array.prototype.forEach.call(loose.body.querySelectorAll('img'), function (image) {
      var id = ((image.getAttribute('src') || '').match(/^media:(m[0-9a-f]{12}\.[a-z0-9]{2,5})$/) || [])[1];
      if (!id) return;
      image.removeAttribute('src');
      image.setAttribute('data-media', id);
    });
    var clean = root.DOMPurify.sanitize(loose.body.innerHTML, { USE_PROFILES: { html: true, mathMl: true }, ADD_ATTR: ['style', 'data-media'] });
    var doc = new DOMParser().parseFromString('<body>' + clean + '</body>', 'text/html');
    if (!title) {
      var first = doc.body.querySelector('h1');
      if (first && first === doc.body.firstElementChild) { title = first.textContent.trim(); first.remove(); }
    }
    // Pictures are shown; anything else attached is named.
    Array.prototype.forEach.call(doc.body.querySelectorAll('img[data-media]'), function (image) {
      var id = image.getAttribute('data-media');
      image.removeAttribute('data-media');
      var kind = id.split('.').pop();
      var address = /^(jpg|jpeg|png|webp|gif)$/.test(kind) ? media(id) : '';
      if (address) { image.setAttribute('src', address); return; }
      var note = doc.createElement('div');
      note.className = 'attachment';
      note.textContent = (kind === 'pdf' ? 'PDF：' : /^(mp4|mov)$/.test(kind) ? '视频：' : /^(jpg|jpeg|png|webp|gif)$/.test(kind) ? '图片（没有取到）：' : '录音：') + (image.getAttribute('alt') || id);
      var block = image.parentElement && image.parentElement.tagName === 'P' && image.parentElement.childNodes.length === 1 ? image.parentElement : image;
      block.replaceWith(note);
    });
    Array.prototype.forEach.call(doc.body.querySelectorAll('a'), function (link) { link.removeAttribute('target'); });
    return { title: title, body: doc.body.innerHTML };
  }

  /** The whole page: the template with its styles, title and body filled in. */
  function page(source, options) {
    var template = options.template;
    [['{{style}}', 1], ['{{body}}', 1], ['{{title}}', 2]].forEach(function (place) {
      if (template.split(place[0]).length - 1 !== place[1]) throw new Error('模板里 ' + place[0] + ' 应该出现 ' + place[1] + ' 次');
    });
    var made = parts(source, options.media || function () { return ''; });
    // The stylesheets go in as text and must not close the <style> they sit in.
    var styles = ((options.katexStyle || '') + '\n' + options.style).replace(/<\/style/gi, '<\\/style');
    return template
      .replace('{{style}}', function () { return styles; })
      .split('{{title}}').join(escape(made.title))
      .replace('{{body}}', function () { return made.body; });
  }

  root.ArticleBuild = { page: page, parts: parts, WIDTH: WIDTH, SCALE: SCALE };
})(typeof window !== 'undefined' ? window : globalThis);
