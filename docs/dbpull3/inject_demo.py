import sqlite3, time

p = r"D:\Doubao\zhuancunmiao\docs\dbpull3\d.db"
c = sqlite3.connect(p)
c.execute("pragma wal_checkpoint(TRUNCATE)")
print("ver", c.execute("pragma user_version").fetchone())
print("tasks", c.execute("select id,name from tasks").fetchall())
print("sources", c.execute("select taskId,position,lastStatus,lastError from task_sources").fetchall())

now = int(time.time() * 1000)
# 演示：给「玫瑰战争」(taskId 10) 加一条失效的附加源；主源标 OK 绿点
rows = c.execute("select id,name from tasks").fetchall()
tid = [r[0] for r in rows if r[1] == "玫瑰战争"][0]
c.execute(
    "insert into task_sources (taskId, position, shareUrl, shareId, passcode, createdAt, lastStatus, lastError, lastCheckedAt) "
    "values (?,?,?,?,?,?,?,?,?)",
    (tid, 1, "https://www.guangyapan.com/s/9999999999_demoExpired", "9999999999_demoExpired", "", now, "FAIL", "分享已被取消或链接已失效", now),
)
c.execute(
    "update task_sources set lastStatus='OK', lastError='', lastCheckedAt=? where taskId=? and position=0",
    (now, tid),
)
c.commit()
print("after", c.execute("select taskId,position,lastStatus,substr(shareUrl,1,36) from task_sources").fetchall())
c.close()
