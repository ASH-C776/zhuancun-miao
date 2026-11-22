"""只读查看转存喵手机端数据库（tasks / snapshots / run_records）。

用法：python tools/read_tasks.py <db 所在目录>
"""
import sqlite3
import sys
from pathlib import Path

d = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/dbcopy")
db = d / "zhuancunmiao.db"
con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
cur = con.cursor()

for table in ("tasks", "snapshots", "run_records"):
    try:
        cur.execute(f"select * from {table} order by 1")
    except Exception as e:
        print(f"-- {table}: {e}")
        continue
    cols = [c[0] for c in cur.description]
    rows = cur.fetchall()
    print(f"\n===== {table} ({len(rows)} 行) =====")
    print(" | ".join(cols))
    for r in rows:
        print(" | ".join(str(v) for v in r))
con.close()
