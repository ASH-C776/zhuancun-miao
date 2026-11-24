import sqlite3
db = sqlite3.connect(r"D:\Doubao\zhuancunmiao\docs\dbpull5\live.db")
cols = [c[1] for c in db.execute("PRAGMA table_info(run_records)")]
print(cols)
for row in db.execute("SELECT * FROM run_records ORDER BY id DESC LIMIT 5"):
    print(row)
