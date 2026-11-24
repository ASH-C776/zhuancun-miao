import sqlite3
db = sqlite3.connect(r"D:\Doubao\zhuancunmiao\docs\dbpull5\live.db")
print("user_version:", db.execute("PRAGMA user_version").fetchone())
for row in db.execute("SELECT id,name,seasonNo,seasonName FROM tasks ORDER BY id"):
    print(row)
