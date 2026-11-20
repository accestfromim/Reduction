"""Exercise original-byte retention, document reading and all CARD export formats."""
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZipFile

import fitz
from PIL import Image
from files import main


def check(tmp):
    req = dict(name="示例/报告", dir=str(tmp/"held"), legacy={}, exports=str(tmp/"exports"))
    def call(action, **kwargs):
        return main({**req, "action": action, **kwargs})
    def add(path):
        return call("import", path=str(path))["id"]
    csv = tmp / "数据.csv"
    csv.write_bytes('编号,备注,空\r\n001,"第一行\r\n第二行",\r\n'.encode("utf-8-sig"))
    key = add(csv)
    assert call("read", id=key)[1] == ["001", "第一行\r\n第二行", ""]
    folder = tmp/"other"
    folder.mkdir()
    duplicate = folder/csv.name
    duplicate.write_text("distinct")
    add(duplicate)
    extra = tmp/"保留.unknown"
    extra.write_bytes(bytes(range(256)))
    add(extra)
    doc = tmp/"报告.docx"
    subprocess.run(["pandoc","-f","markdown","-o",doc],input="# 标题\n\n第一段".encode(),check=True)
    assert "第一段" in call("read", id=add(doc))
    img = tmp/"sample.png"
    Image.new("RGB",(64,32),"green").save(img)
    image_id = add(img)
    req["text"] = ('<html><body><p>REPORT-42</p><table data-chart="share"><tbody>'
                   '<tr><th>A</th><td>75</td></tr><tr><th>B</th><td>25</td></tr></tbody></table>'
                   f'<img data-attachment="media:{image_id}" alt="样图"></body></html>')
    html = Path(call("export",format="html")).read_text()
    assert "75.0%" in html and "data:image/png;base64," in html and f"media:{image_id}" not in html
    with ZipFile(call("export",format="markdown")) as z:
        md = z.read("card.md").decode()
        assert "75.0%" not in md and "75" in md and "assets/" in md
    with fitz.open(call("export", format="pdf")) as pdf:
        assert "REPORT-42" in pdf[0].get_text()
    assert Image.open(call("export",format="png")).width == 1000
    with ZipFile(call("export",format="zip")) as z:
        manifest = json.loads(z.read("manifest.json"))
        assert len(manifest) == 5
        for entry in manifest:
            assert hashlib.sha256(z.read(entry["path"])).hexdigest() == entry["sha256"]
        assert z.read("card.html").decode() == req["text"]
    req["text"] = '<html><body>' + '<p>tall</p>' * 320 + '</body></html>'
    with ZipFile(call("export",format="png")) as z:
        assert len(z.namelist()) >= 2
    (Path(req["dir"])/key/"original").unlink()
    try:
        call("export",format="zip")
    except ValueError as e:
        assert csv.name in str(e)
    else:
        raise AssertionError("缺附件却报告成功")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as tmp:
        check(Path(tmp))
    print("CARD checks passed")
