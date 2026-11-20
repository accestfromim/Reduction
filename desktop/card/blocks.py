"""Edit semantic CARD components against one revision of an HTML document."""
from html.parser import HTMLParser
import hashlib
import subprocess
from pathlib import Path
import json
import re
import sys
from urllib.parse import urlsplit

from bs4 import BeautifulSoup, NavigableString


def revision(source):
    return hashlib.sha256(source.encode()).hexdigest()[:16]


def blocks(doc):
    tags = doc.body.select("h1,h2,h3,h4,h5,h6,p,blockquote,table,figure,a,ol[data-timeline] > li > span,ol[data-timeline] > li > strong") if doc.body else []
    return [tag for tag in tags if not tag.find_parent(["table", "figure", "blockquote", "p", "a", "h1", "h2", "h3", "h4", "h5", "h6"])]


def string(value):
    if not isinstance(value, str):
        raise ValueError("需要文本字段")
    return value


def element(doc, name, value=None, **attrs):
    tag = doc.new_tag(name, attrs=attrs)
    if value is not None:
        tag.string = string(value)
    return tag


def chart(table, view):
    if view not in ("table", "share", "bar"):
        raise ValueError("view 可选 table、share、bar")
    if view == "table":
        table.attrs.pop("data-chart", None)
        return
    rows = table.select("tbody > tr")
    cells = [[cell.get_text() for cell in row.find_all(["th", "td"], recursive=False)] for row in rows]
    validator = Path(__file__).with_name("validate-chart.mjs")
    result = subprocess.run(["node", validator], input=json.dumps([view, cells]), text=True, timeout=10)
    if result.returncode:
        raise ValueError("图表需要两列：标签和非负数值，且至少一个数值大于零")
    table["data-chart"] = view


def build(doc, change, files):
    kind = change["type"]
    if kind == "text":
        style = change.get("style", "p")
        if style not in ("p", "h1", "h2", "h3", "blockquote"):
            raise ValueError("不支持的文字样式")
        return element(doc, style, change["text"])
    if kind == "quote":
        text = string(change["quote"])
        url = string(change["source"])
        parsed = urlsplit(url)
        if not text.strip() or parsed.scheme != "https" or not parsed.hostname or any(c.isspace() for c in url):
            raise ValueError("引文需要非空原文和完整的 HTTPS 来源链接")
        quote = element(doc, "blockquote", cite=url)
        quote.append(element(doc, "p", text))
        footer = element(doc, "footer")
        footer.append(element(doc, "a", parsed.hostname, href=url))
        quote.append(footer)
        return quote
    if kind == "timeline":
        entries = change["timeline"]
        if not isinstance(entries, list) or not entries:
            raise ValueError("时间轴至少需要一个事件")
        timeline = element(doc, "ol", **{"data-timeline": ""})
        for pair in entries:
            if not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(v,str) and v.strip() for v in pair):
                raise ValueError("每项需要非空的时间和事件文字")
            item = element(doc, "li")
            item.append(element(doc, "strong", pair[0]))
            item.append(" · ")
            item.append(element(doc, "span", pair[1]))
            timeline.append(item)
        return timeline
    if kind == "table":
        table = element(doc, "table")
        if "caption" in change:
            table.append(element(doc, "caption", change["caption"]))
        rows = change["rows"]
        if not isinstance(rows, list) or not rows or not isinstance(rows[0], list) or not rows[0]:
            raise ValueError("rows 需要非空二维数组")
        width = len(rows[0])
        def add_row(group, values, header):
            if not isinstance(values, list) or len(values) != width:
                raise ValueError("表格各行列数应一致")
            tr = element(doc, "tr")
            for v in values:
                if not isinstance(v, (str, int, float)) or isinstance(v, bool):
                    raise ValueError("单元格需要文字或数字")
                tr.append(element(doc, "th" if header else "td", str(v)))
            group.append(tr)
        if "columns" in change:
            head = element(doc, "thead")
            add_row(head, change["columns"], True)
            table.append(head)
        body = element(doc, "tbody")
        for row in rows:
            add_row(body, row, False)
        table.append(body)
        chart(table, change.get("view", "table"))
        return table
    if kind == "link":
        url = string(change["url"])
        if urlsplit(url).scheme.lower() == "https":
            attrs = {"href": url}
        elif (url.startswith("/") and not url.startswith("//")) or re.match(r"^[A-Za-z]:[\\/]", url):
            attrs = {"data-path": url}
        else:
            raise ValueError("链接需要 https 或绝对路径")
        return element(doc, "a", change["text"], **attrs)
    if kind == "media":
        key = change["id"]
        mime = files.get(key, "").split("/")[0]
        if mime not in ("image", "audio", "video"):
            raise ValueError("需要已挂载的图片、音频或视频 ID")
        fig = element(doc, "figure")
        tag = element(doc, "img" if mime == "image" else mime, **{"data-attachment": "media:" + key})
        if mime == "image":
            tag["alt"] = string(change.get("caption", ""))
        else:
            tag["controls"] = ""
        fig.append(tag)
        if "caption" in change:
            fig.append(element(doc, "figcaption", change["caption"]))
        return fig
    raise ValueError("未知组件类型")


def source_bounds(source, tag):
    """Locate an explicitly closed component without reserializing its neighbours."""
    lines = source.splitlines(keepends=True)
    start = sum(map(len, lines[:tag.sourceline-1])) + tag.sourcepos
    class Boundary(HTMLParser):
        depth = 0
        stop = None
        def handle_starttag(self, name, attrs):
            if self.stop is None and name == tag.name:
                self.depth += 1
        def handle_endtag(self, name):
            if self.stop is None and name == tag.name:
                self.depth -= 1
                if self.depth == 0:
                    line, col = self.getpos()
                    self.stop = sum(map(len, fragment_lines[:line-1])) + col
    fragment = source[start:]
    fragment_lines = fragment.splitlines(keepends=True)
    parser = Boundary()
    parser.feed(fragment)
    if parser.stop is None:
        raise ValueError("组件缺少明确的闭合标签，请使用精准补丁")
    closing = start + parser.stop
    return start, closing, source.index(">", closing) + 1


def named_cell(items, change):
    allowed = {"table", "row", "column", "text"}
    if change.keys() - allowed or "text" not in change:
        raise ValueError("按名称改单元格需要 row 和 text，可选 table、column")
    label = string(change["row"]).strip()
    if not label:
        raise ValueError("行名为空")
    matches = []
    for number, table in enumerate(items, 1):
        if table.name != "table":
            continue
        caption = table.find("caption", recursive=False)
        if "table" in change and (not caption or caption.get_text(strip=True) != change["table"]):
            continue
        rows = [row for row in table.find_all("tr") if row.find_parent("table") is table and not row.find_parent(["thead", "tfoot"])]
        for row in rows:
            cells = row.find_all(["td", "th"], recursive=False)
            if not cells or cells[0].get_text(strip=True) != label:
                continue
            if table.select_one("[rowspan], [colspan], table"):
                raise ValueError("复杂表格需要按原文核对")
            if "column" in change:
                headers = table.select("thead > tr")
                if len(headers) != 1:
                    raise ValueError("缺少唯一表头")
                columns = [i for i,c in enumerate(headers[0].find_all(["td","th"],recursive=False))
                           if c.get_text(strip=True) == change["column"]]
                if len(columns) != 1 or columns[0] >= len(cells):
                    continue
                col = columns[0]
            elif len(cells) == 2:
                col = 1
            else:
                raise ValueError("请指定列名")
            matches.append((number, cells[col]))
    if len(matches) != 1:
        raise ValueError("找不到唯一目标，请核对表名、行名和列名")
    return matches[0]


def edit(req):
    source = req["source"]
    if source.strip() and not re.match(r"\s*(?:<!doctype\s+html\b|<html\b)", source, re.I):
        raise ValueError("此工具编辑 HTML 卡片，普通 Markdown 卡可用 patch_cards")
    template = source or "<!doctype html><html><head></head><body></body></html>"
    doc = BeautifulSoup(template, "html.parser")
    if not doc.body:
        raise ValueError("HTML 卡片缺少 body")
    items = blocks(doc)
    rev = revision(source)
    change = req.get("change")
    if change is None:
        labels = [(t.caption if t.name=="table" and t.caption else t).get_text(" ", strip=True) for t in items]
        return dict(blocks=[dict(tag=t.name, target=label) if label and len(label)<=60 and labels.count(label)==1
                            else dict(tag=t.name, preview=label[:60], ref=f"{rev}.{i}")
                            for i,(t,label) in enumerate(zip(items, labels),1)])
    number = change.get("block")
    named = "row" in change
    by_target = "target" in change
    if by_target:
        label = string(change["target"]).strip()
        matches = [i for i,t in enumerate(items,1) if label and
                   (t.caption if t.name=="table" and t.caption else t).get_text(" ", strip=True) == label]
        if len(matches) != 1:
            raise ValueError("目标未唯一匹配，请查看组件目录")
        number = matches[0]
    if named:
        number, selected = named_cell(items, change)
    elif "ref" in change:
        token, sep, index = string(change["ref"]).partition(".")
        if token != rev or not sep or not index.isdecimal():
            raise ValueError("卡片内容已变化，请重新定位")
        number = int(index)
    if number is None:
        component = build(doc, change, req.get("files", {}))
        _, closing, _ = source_bounds(template, doc.body)
        output = template[:closing] + str(component) + template[closing:]
        doc.body.append(component)
    else:
        if not named and not by_target and "ref" not in change and req.get("revision") != rev:
            raise ValueError("正文版本已变化，请重新读取组件目录")
        if type(number) != int or not 1 <= number <= len(items):
            raise ValueError("组件编号不存在")
        tag = items[number-1]
        target = selected if named else tag
        if "cell" in change:
            row, col = change["cell"]
            if tag.name != "table" or any(type(i) != int or i < 1 for i in (row,col)):
                raise ValueError("cell 需要表体行列编号，从 1 开始")
            target = selected if named else tag.select("tbody > tr")[row-1].find_all(["td","th"],recursive=False)[col-1]
        if "text" in change:
            if target.name in ("table","figure"):
                raise ValueError("请定位到具体单元格或文字组件")
            if target.find(True) and target.string is None:
                raise ValueError("含混合富文本，请用精准补丁保留格式")
            if isinstance(target.string, NavigableString):
                target.string.replace_with(string(change["text"]))
            else:
                target.string = string(change["text"])
        if "view" in change or ((named or "cell" in change) and tag.get("data-chart")):
            if tag.name != "table":
                raise ValueError("view 仅适用于表格")
            chart(tag, change.get("view",tag.get("data-chart")))
        if not ({"text","view"} & change.keys()):
            raise ValueError("请输入 text 或 view")
        start, _, end = source_bounds(template, tag)
        output = template[:start] + str(tag) + template[end:]
    return dict(source=output, revision=revision(output), block=number or len(blocks(doc)))


if __name__ == "__main__":
    try:
        print(json.dumps(edit(json.load(sys.stdin)), ensure_ascii=False))
    except (KeyError, IndexError, TypeError, ValueError) as error:
        sys.exit("组件编辑失败：" + str(error))
