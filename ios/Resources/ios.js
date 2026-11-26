(() => {
  function call(method, args) {
    const request = new XMLHttpRequest();
    request.open('POST', `/bridge/${method}`, false);
    request.setRequestHeader('X-Reduction', '1');
    request.setRequestHeader('Content-Type', 'application/json');
    request.send(JSON.stringify(args));
    if (request.status !== 200) return undefined;
    const reply = JSON.parse(request.responseText);
    return reply.result ?? undefined;
  }

  let opening = true;
  const here = {
    requestMicPermission() {
      call('requestMicPermission', [!opening]);
      opening = false;
    }
  };

  window.Android = new Proxy({}, { get: (_, method) => here[method] || ((...args) => call(method, args)) });

  addEventListener('DOMContentLoaded', () => {
    const apiField = document.getElementById('apiKeyInput')?.closest('label');
    if (apiField) {
      const note = document.createElement('p');
      note.className = 'app-info';
      note.textContent = '填写并保存密钥后，对话、对话图片和主动录下的语音会发送给 DeepSeek。';
      apiField.after(note);
    }
    const article = document.getElementById('cardArticle');
    const frame = document.getElementById('cardArticleFrame');
    const exportButton = document.createElement('button');
    exportButton.type = 'button';
    exportButton.className = 'text-btn';
    exportButton.textContent = '导出长图';
    exportButton.hidden = true;
    document.getElementById('cardPreview').after(exportButton);
    const updateExport = () => { exportButton.hidden = article.classList.contains('hidden'); };
    new MutationObserver(updateExport).observe(article, { attributes: true, attributeFilter: ['class'] });
    exportButton.addEventListener('click', () => {
      const page = frame.contentDocument;
      if (!page?.documentElement || !frame.srcdoc) return;
      const height = Math.ceil(page.documentElement.getBoundingClientRect().height);
      call('exportArticle', [page.documentElement.outerHTML, height, page.title]);
    });
    call('pageReady', []);
  });
})();
