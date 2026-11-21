"""Local CARD projection. The writer explicitly chooses separate public and author-only files."""
import argparse
import html
import json
import os
import re
from pathlib import Path

RULE = json.loads((Path(__file__).resolve().parents[2] / "app/src/main/assets/article/privacy.json").read_text())
TAG = re.compile(RULE["pattern"])


def project(source):
    marker = re.compile(r"data-card-privacy|</?card-private", re.I)
    if not marker.search(source):
        return source, []
    audit = []
    def replace(m):
        audit.append(dict(line=source.count("\n", 0, m.start()) + 1, original=html.unescape(m.group(2) if m.group(2) is not None else m.group(3))))
        return ""
    result = TAG.sub(replace, source)
    if marker.search(result):
        raise ValueError("隐私标记不完整或含嵌套元素，请修正后再导出")
    return result, audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("public", type=Path)
    parser.add_argument("audit", type=Path)
    args = parser.parse_args()
    public, audit = project(args.source.read_text())
    if args.public.resolve() == args.audit.resolve() or args.public.exists() or args.audit.exists():
        parser.error("choose two different new output files")
    with open(os.open(args.audit, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf8") as stream:
        json.dump(audit, stream, ensure_ascii=False, indent=2)
    with args.public.open("x", encoding="utf8") as stream:
        stream.write(public)


if __name__ == "__main__":
    main()
