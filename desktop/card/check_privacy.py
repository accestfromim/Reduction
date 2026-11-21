import json
import tempfile
from pathlib import Path
from zipfile import ZipFile
import fitz
from PIL import Image, ImageChops
from blocks import edit
from files import main
from privacy import project

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    def export(fmt, text):
        return Path(main(dict(action="export", format=fmt, name="Test", dir=str(root/"held"),
                              exports=tmp, legacy={}, text=text)))
    body = '<html><body><p>PUBLIC</p><p>Hello PRIVATE_CANARY_743.</p></body></html>'
    source = edit(dict(source=body, change=dict(note='PRIVATE_CANARY_743')))['source']
    public = body.replace('PRIVATE_CANARY_743','')
    assert project(source)[0] == public
    assert project(body) == (body, [])
    try:
        project(source.replace('</card-private>', ''))
    except ValueError:
        pass
    else:
        raise AssertionError('malformed tag must block export')
    for fmt in ('html','markdown','pdf','png','zip'):
        f = export(fmt, source)
        if fmt == 'pdf':
            with fitz.open(f) as doc:
                visible = ''.join(page.get_text() for page in doc)
        elif fmt == 'png':
            a, b = Image.open(f), Image.open(export(fmt, public))
            assert a.size == b.size and ImageChops.difference(a.convert('RGB'),b.convert('RGB')).getbbox() is None
            continue
        elif fmt == 'zip':
            with ZipFile(f) as archive:
                visible = archive.read('card.html').decode()
        else:
            visible = f.read_text()
        assert 'PUBLIC' in visible and 'PRIVATE_CANARY_743' not in visible, fmt
    text = '公开进度\n<span data-card-privacy="person">测试姓名</span>完成测试\n微信来源：内部消息\n未审核'
    filtered, audit = project(text)
    assert filtered == '公开进度\n完成测试\n微信来源：内部消息\n未审核'
    assert audit == [dict(line=2, original='测试姓名')]
print('HTML, Markdown, PDF text, PNG pixels, ZIP and report redactions passed')
