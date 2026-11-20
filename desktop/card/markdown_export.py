"""Project CARD source components onto portable GitHub-flavored Markdown."""
from pathlib import PureWindowsPath
from urllib.parse import quote, urlsplit

from bs4 import BeautifulSoup


def convert(source, files, is_html, run):
    if not is_html and not files and "media:" not in source:
        return source.encode()
    if not is_html:
        source = run(["pandoc", "-f", "gfm", "-t", "html5"], input=source.encode()).decode()
    doc = BeautifulSoup(source, "html.parser")
    for el in doc.select("head,script,style,iframe,object,template,form,svg,.reduction-chart-output,[hidden]"):
        el.decompose()
    for paragraph in doc.select("blockquote[cite] > p"):
        for node in list(paragraph.find_all(string=True)):
            if "\n" in node:
                for index, line in enumerate(node.split("\n")):
                    if index:
                        node.insert_before(doc.new_tag("br"))
                    node.insert_before(line)
                node.extract()
    used = set()

    def asset(key):
        if key not in files:
            raise ValueError("缺失附件 " + key)
        used.add(key)
        return "assets/" + key + "/" + quote(files[key][1]["name"], safe="")

    for el in doc.find_all(True):
        if el.name is None:
            continue
        path = el.get("data-path") if el.name == "a" else None
        attr = "href" if el.name == "a" else "src"
        ref = el.get("data-attachment") or el.get(attr, "")
        if ref.startswith("media:"):
            el[attr] = asset(ref[6:])
        elif path:
            if path.startswith("/") and not path.startswith("//"):
                el["href"] = "file://" + quote(path)
            elif PureWindowsPath(path).drive and PureWindowsPath(path).is_absolute():
                el["href"] = PureWindowsPath(path).as_uri()
        elif ref and urlsplit(ref).scheme.lower() not in ("", "https", "http", "mailto"):
            el.attrs.pop(attr, None)
        if el.name in ("video", "audio"):
            src = el.get("src") or ""
            if not src and el.source:
                nested = el.source.get("data-attachment") or el.source.get("src", "")
                src = asset(nested[6:]) if nested.startswith("media:") else nested
            link = doc.new_tag("a")
            if src and urlsplit(src).scheme in ("", "https", "http"):
                link["href"] = src
            link.string = "视频" if el.name == "video" else "音频"
            el.replace_with(link)
            continue
        if el.name == "caption" and el.parent.name == "table":
            table = el.parent
            el.name = "p"
            table.insert_before(el.extract())
        el.attrs = {k:v for k,v in el.attrs.items() if k in ("href","src","alt","title","rowspan","colspan","start")}
    for footer in doc.select("blockquote > footer"):
        footer.name = "p"
    for caption in doc.find_all("figcaption"):
        caption.name = "p"
    for figure in doc.find_all("figure"):
        figure.name = "div"
        for child in list(figure.find_all(["img", "a"], recursive=False)):
            child.wrap(doc.new_tag("p"))
    for wrapper in doc.select("figure, section, article, main, div"):
        wrapper.unwrap()
    for key, (_, meta) in files.items():
        if key not in used:
            link = doc.new_tag("a", href=asset(key))
            link.string = meta["name"]
            paragraph = doc.new_tag("p")
            paragraph.append(link)
            (doc.body or doc).append(paragraph)
    return run(["pandoc", "-f", "html", "-t", "gfm", "--wrap=none"], input=str(doc).encode())
