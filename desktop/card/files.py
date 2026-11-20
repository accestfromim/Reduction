"""Desktop CARD worker. The host resolves card identities and authorizes input paths."""
import base64
import csv
import hashlib
import io
import json
import mimetypes
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from markdown_export import convert as to_markdown
from zipfile import ZipFile, ZIP_DEFLATED
from xml.etree import ElementTree as ET


def dump(value):
    return json.dumps(value, ensure_ascii=False)


def run(args, **kw):
    result = subprocess.run(args, capture_output=True, timeout=120, **kw)
    if result.returncode:
        raise ValueError(result.stderr.decode(errors="replace")[-1000:] or "转换失败")
    return result.stdout


def text_decode(data):
    if data.startswith((b"\xfe\xff", b"\xff\xfe")):
        return data.decode("utf-16")
    for encoding in ("utf-8-sig", "gb18030"):
        try:
            return data.decode(encoding)
        except UnicodeError:
            pass
    raise ValueError("无法确定文本编码，原件已保留")


def info(path, name, identity):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return dict(id=identity, name=name, size=path.stat().st_size, sha256=digest,
                type=mimetypes.guess_type(name)[0] or "application/octet-stream")


def attach(root, source):
    root.mkdir(parents=True, exist_ok=True)
    identity = uuid.uuid4().hex
    with tempfile.TemporaryDirectory(dir=root, prefix=".partial-") as tmp:
        stage = Path(tmp) / identity
        stage.mkdir()
        blob = stage / "original"
        shutil.copyfile(source, blob)
        meta = info(blob, source.name, identity)
        (stage / "meta.json").write_text(dump(meta))
        stage.rename(root / identity)
    return meta


def inventory(req):
    root = Path(req["dir"])
    files = {}
    for directory in sorted(root.glob("[0-9a-f]*")):
        meta = json.loads((directory / "meta.json").read_text())
        files[meta["id"]] = (directory / "original", meta)
    for key, path in req["legacy"].items():
        if not path or not Path(path).is_file():
            raise ValueError("缺失附件 " + key)
        files[key] = (Path(path), info(Path(path), key, key))
    return files


def legacy_document(path):
    prefix = Path.home() / ".local/share/reduction/card-readers"
    command = ["bwrap", "--unshare-all", "--die-with-parent", "--ro-bind", "/usr", "/usr",
               "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
               "--ro-bind", "/etc", "/etc", "--dev", "/dev", "--tmpfs", "/tmp",
               "--tmpfs", "/usr/share", "--ro-bind", str(prefix / "usr/share/catdoc"), "/usr/share/catdoc",
               "--ro-bind", str(prefix / "usr/bin/catdoc"), "/catdoc",
               "--ro-bind", str(path.resolve()), "/input.doc", "/catdoc", "-w", "-d", "utf-8", "/input.doc"]
    return run(command).decode()


def spreadsheet(path):
    import posixpath
    with ZipFile(path) as archive:
        def xml(name):
            return ET.fromstring(archive.read(name))
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared = ["".join(n.text or "" for n in item.iterfind(".//{*}t"))
                      for item in xml("xl/sharedStrings.xml")]
        targets = {r.get("Id"): r.get("Target") for r in xml("xl/_rels/workbook.xml.rels")}
        sheets = []
        for sheet in xml("xl/workbook.xml").findall(".//{*}sheet"):
            rid = next(v for k, v in sheet.attrib.items() if k.endswith("}id"))
            location = posixpath.normpath(posixpath.join("xl", targets[rid])).lstrip("/")
            rows = []
            for row in xml(location).iterfind(".//{*}row"):
                cells = {}
                for cell in row.findall("{*}c"):
                    value = cell.findtext("{*}v", "")
                    if cell.get("t") == "inlineStr":
                        value = "".join(n.text or "" for n in cell.iterfind(".//{*}t"))
                    elif cell.get("t") == "s":
                        value = shared[int(value)]
                    cells[cell.get("r")] = value
                rows.append(cells)
            sheets.append({"sheet": sheet.get("name"), "rows": rows})
        return sheets


def read_file(path, meta):
    ext = Path(meta["name"]).suffix.lower().lstrip(".")
    if ext == "doc":
        return legacy_document(path)
    if ext == "xls":
        sys.path.append(str(Path.home()/".local/share/reduction/card-readers/usr/lib/python3/dist-packages"))
        import xlrd
        with xlrd.open_workbook(path, on_demand=True) as book:
            return [{"sheet": sheet.name, "rows": [sheet.row_values(i) for i in range(sheet.nrows)]}
                    for sheet in book.sheets()]
    if ext == "xlsx":
        return spreadsheet(path)
    if ext == "docx":
        return run(["pandoc", "-f", "docx", "-t", "gfm", str(path)]).decode()
    if ext in ("csv", "tsv"):
        content = text_decode(path.read_bytes())
        delimiter = "\t" if ext == "tsv" else ","
        try:
            delimiter = csv.Sniffer().sniff(content[:8192], delimiters=",;\t").delimiter
        except csv.Error:
            pass
        return list(csv.reader(io.StringIO(content, newline=""), delimiter=delimiter))
    if ext == "pdf":
        import fitz
        with fitz.open(path) as document:
            if document.needs_pass:
                raise ValueError("PDF 受密码保护")
            return [{"page": i + 1, "text": p.get_text()} for i, p in enumerate(document)]
    if ext in ("md", "txt", "html", "htm", "json", "xml", "log", "yaml", "yml"):
        return text_decode(path.read_bytes())
    return {"original": meta, "status": "保留原件"}


def write_bundle(target, body, filename, files, folder):
    manifest = []
    with ZipFile(target, "w", ZIP_DEFLATED) as archive:
        archive.writestr(filename, body)
        for key, (path, meta) in files.items():
            entry = f"{folder}/{key}/{Path(meta['name']).name}"
            if not path.is_file() or info(path, meta["name"], key)["sha256"] != meta["sha256"]:
                raise ValueError("附件缺失或校验失败：" + meta["name"])
            archive.write(path, entry)
            manifest.append({**meta, "path": entry})
        archive.writestr("manifest.json", dump(manifest))


def export(req, files):
    fmt = req["format"]
    if fmt not in ("html", "markdown", "pdf", "png", "zip"):
        raise ValueError("未知导出格式")
    exports = Path(req["exports"])
    exports.mkdir(parents=True, exist_ok=True)
    title = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", req["name"])[:100] or "card"
    with tempfile.TemporaryDirectory(dir=exports, prefix=".partial-") as tmp:
        dest = Path(tmp)
        body = req["text"]
        is_html = bool(re.match(r"\s*(?:<!doctype\s+html|<html\b)", body, re.I))
        if fmt == "zip":
            artifact = dest / (title + ".zip")
            write_bundle(artifact, body, "card." + ("html" if is_html else "md"), files, "attachments")
        elif fmt == "markdown":
            md = to_markdown(body, files, is_html, run)
            if files:
                artifact = dest / "output.zip"
                write_bundle(artifact, md, "card.md", files, "assets")
            else:
                artifact = dest / "output.md"
                artifact.write_bytes(md)
        else:
            html = body if is_html else run(["pandoc", "-f", "gfm", "-t", "html5"], input=body.encode()).decode()
            task = {"html": html, "files": {k: [str(p), m] for k, (p, m) in files.items()},
                    "dir": tmp, "format": fmt}
            run(["node", str(Path(__file__).with_name("render") / "export.mjs")], input=dump(task).encode())
            artifact = dest / ("output." + fmt)
            if fmt == "png" and not artifact.exists():
                artifact = dest / "output.zip"
                with ZipFile(artifact, "w") as archive:
                    for part in sorted(dest.glob("part-*.png")):
                        archive.write(part, part.name)
        final = exports / (title + "-" + uuid.uuid4().hex[:12] + artifact.suffix)
        artifact.rename(final)
        return str(final)


def main(req):
    if req["action"] == "import":
        return attach(Path(req["dir"]), Path(req["path"]))
    files = inventory(req)
    if req["action"] == "list":
        return [meta for _, meta in files.values()]
    if req["action"] == "read":
        if req["id"] not in files:
            raise ValueError("卡片没有这个附件")
        return read_file(*files[req["id"]])
    if req["action"] == "export":
        return export(req, files)
    raise ValueError("未知操作")


if __name__ == "__main__":
    try:
        print(dump(main(json.load(sys.stdin))))
    except Exception as error:
        sys.exit(str(error))
