import sqlite3, time, shutil, os

base = r"D:\Doubao\zhuancunmiao\docs\dbpull3"
src = os.path.join(base, "orig.db")
dst = os.path.join(base, "demo2.db")
shutil.copyfile(src, dst)

c = sqlite3.connect(dst)
now = int(time.time() * 1000)

c.execute("update task_sources set lastStatus='OK', lastError='', lastCheckedAt=? where taskId=10 and position=0", (now,))

ins = (
    "insert into task_sources (taskId, position, shareUrl, shareId, passcode, createdAt, lastStatus, lastError, lastCheckedAt) "
    "values (?,?,?,?,?,?,?,?,?)"
)
c.execute(ins, (10, 1, "https://www.guangyapan.com/s/1965876_second", "1965876_second", "", now, "FAIL",
                "分享已被取消或链接已失效", now))
c.execute(ins, (10, 2, "https://www.guangyapan.com/s/1965876_third", "1965876_third", "", now, "WARN",
                "上次没连上：网络超时", now))
c.commit()
print(c.execute("select taskId,position,lastStatus,lastError from task_sources").fetchall())
c.close()
