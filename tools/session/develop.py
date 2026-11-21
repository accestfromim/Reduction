"""Run Codex on explicitly selected Reduction sources. Return a local diff for review."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
request = json.load(sys.stdin)
prompt = request["prompt"]
files = request["files"]
if not isinstance(prompt,str) or not prompt.strip() or not isinstance(files,list) or not 1 <= len(files) <= 32:
    sys.exit("请提供开发任务和 1 到 32 个仓库源码路径")
base = Path.home()/".local/share/reduction/development"
base.mkdir(parents=True,exist_ok=True)
job = Path(tempfile.mkdtemp(prefix="job-",dir=base))
work = job/"work"
work.mkdir()
def git(*args):
    return subprocess.run(["git","-C",str(work),*args],check=True,stdout=subprocess.PIPE).stdout
git("init","-q")
for name in files:
    relative = Path(name)
    src = root/relative
    if relative.is_absolute() or src.resolve()!=src or not src.is_file() or relative.suffix not in {".js",".mjs",".kt",".kts",".css",".html",".md",".py",".cs",".sh"}:
        sys.exit("无效的项目源码路径")
    if any(part.startswith(".") for part in relative.parts) or subprocess.run(["git","-C",str(root),"check-ignore","-q","--",name]).returncode != 1:
        sys.exit("仅可选择项目中未忽略的源码文件")
    dest = work/relative
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_bytes(src.read_bytes())
git("add","--",".")
git("-c","user.name=Reduction","-c","user.email=local@localhost","-c","commit.gpgsign=false","commit","-qm","source snapshot")
result = job/"result.txt"
cmd = ["codex","exec","--json","--sandbox","workspace-write","--cd",str(work),"-o",str(result),"-"]
with (job/"events.jsonl").open("wb") as events,(job/"stderr.log").open("wb") as errors:
    run = subprocess.run(cmd,input=("在当前源码副本完成任务，实际编辑文件；保留 Git 基线，不提交或发布。\n"+prompt).encode(),cwd=work,stdout=events,stderr=errors,timeout=240)
if run.returncode:
    sys.exit(f"Codex 退出 {run.returncode}，诊断目录 {job}")
git("add","-N","--",".")
patch = git("diff","--no-ext-diff","HEAD","--",*files)
(job/"change.patch").write_bytes(patch)
session = None
for line in (job/"events.jsonl").read_text().splitlines():
    event=json.loads(line)
    if event.get("type")=="thread.started": session=event.get("thread_id")
print(json.dumps(dict(session=session,result=result.read_text(),patch=str(job/"change.patch"),changed=bool(patch)),ensure_ascii=False))
