import sqlite3
db = sqlite3.connect(r"D:\Doubao\zhuancunmiao\docs\dbpull5\live.db")
cols=[c[1] for c in db.execute("PRAGMA table_info(tasks)")]
row = db.execute("SELECT * FROM tasks WHERE id=18").fetchone()
for k,v in zip(cols,row): print(k,"=",v)
