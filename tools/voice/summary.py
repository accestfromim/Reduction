"""Tabulate complete traces from journal files, plus incomplete and failed IDs."""
import sys
from collections import defaultdict
from statistics import median

turns = defaultdict(dict)
for filename in sys.argv[1:]:
    with open(filename) as stream:
        for line in stream:
            fields = dict(field.split("=", 1) for field in line.split())
            turns[fields["id"]][fields["stage"]] = int(fields["ts_ms"])

stages = ["release", "asrstart", "asrend", "token", "audio"]
rows = []
print("| ID | 松开到识别 | 识别 | 识别到首字 | 首字到音频 | 总首响 ms |")
print("|---|---:|---:|---:|---:|---:|")
for trace, events in turns.items():
    if not all(stage in events for stage in stages):
        print(trace, "incomplete:", ",".join(events), file=sys.stderr)
        continue
    times = [events[s] for s in stages]
    row = [b-a for a, b in zip(times, times[1:])] + [times[-1]-times[0]]
    rows.append(row)
    print("|", " | ".join(map(str, [trace[:8], *row])), "|")
if rows:
    for label, fn in (("最小", min), ("中位", median), ("最大", max)):
        print("|", " | ".join(map(str, [label, *map(fn, zip(*rows))])), "|")
