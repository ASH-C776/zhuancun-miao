import sqlite3

c = sqlite3.connect(r"D:\Doubao\zhuancunmiao\docs\dbpull2\d.db")
print("ver", c.execute("pragma user_version").fetchone())
print("tables", [r[0] for r in c.execute("select name from sqlite_master where type='table'")])
print("cols", [r[1] for r in c.execute("pragma table_info(task_sources)")])
print("idx", [r[0] for r in c.execute("select name from sqlite_master where type='index' and tbl_name='task_sources'")])
print("tasks", c.execute("select count(*) from tasks").fetchone()[0],
      "sources", c.execute("select count(*) from task_sources").fetchone()[0])
