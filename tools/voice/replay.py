"""Replay WAV files through an already running, isolated desktop instance."""
import argparse
import json
import queue
import threading
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("port", type=int)
parser.add_argument("wav", nargs="+", type=Path)
args = parser.parse_args()
base = f"http://127.0.0.1:{args.port}"
events = queue.Queue()
connection = urllib.request.urlopen(base + "/events", timeout=120)


def listen():
    for line in connection:
        if line.startswith(b"data: {"):
            events.put(json.loads(line[6:]))


def post(path, payload):
    req = urllib.request.Request(base + path, data=payload, headers={"X-Reduction": "1"})
    with urllib.request.urlopen(req, timeout=90) as response:
        return json.load(response)


def bridge(method, *values):
    return post("/bridge/" + method, json.dumps(values).encode())


threading.Thread(target=listen, daemon=True).start()
for wav in args.wav:
    data = wav.read_bytes()
    trace = uuid.uuid4().hex
    bridge("voiceStart", trace)
    bridge("voiceRelease", trace)
    post("/asr?id=" + trace, data)
    while True:
        evt = events.get(timeout=90)
        values = evt.get("args", [])
        if evt.get("signal") == "audioEnd":
            post("/audio-done?session=" + str(evt["session"]), b"{}")
        if values[:1] != [trace]:
            continue
        name = evt["fn"]
        if name == "onAsrResult" and values[1]:
            bridge("submitVoice", trace, values[2], "[]")
        elif name in ("onAsrResult", "onTtsDone", "onChatError", "onTtsError"):
            print(trace, name, flush=True)
            break
