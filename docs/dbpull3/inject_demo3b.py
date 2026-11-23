import sqlite3, time, shutil, os

base = r"D:\Doubao\zhuancunmiao\docs\dbpull3"
src = os.path.join(base, "v10.db")
dst = os.path.join(base, "demo3b.db")
shutil.copyfile(src, dst)

c = sqlite3.connect(dst)
now = int(time.time() * 1000)

tasks_cols = [r[1] for r in c.execute("pragma table_info(tasks)").fetchall()]

t = {k: "" for k in tasks_cols}
t.update({
    "name": "演示·无TMDB已最新",
    "driveType": "GUANGYA",
    "shareUrl": "https://www.guangyapan.com/s/1965876_demo",
    "shareId": "1965876_demo",
    "passcode": "",
    "saveFid": "0",
    "savePathLabel": "光鸭云盘",
    "seasonFid": "",
    "seasonName": "",
    "startFid": "",
    "scopeMode": "FLAT",
    "tmdbId": None,
    "tmdbMediaType": "tv",
    "updateMode": "MANUAL",
    "weekdays": "1,2,3,4,5,6,7",
    "airTime": "",
    "enabled": 1,
    "createdAt": now,
    "updatedAt": now,
    "ruleKey": "",
    "seasonNo": 0,
    "folderWithTmdbId": 0,
})
cols = [k for k in tasks_cols if k != "id"]
sql = "insert into tasks ({}) values ({})".format(",".join(cols), ",".join("?" * len(cols)))
c.execute(sql, [t[k] for k in cols])
tid1 = c.execute("select last_insert_rowid()").fetchone()[0]

t2 = dict(t)
t2.update({"name": "演示·无TMDB刚转存", "shareUrl": "https://www.guangyapan.com/s/1956900_demo", "shareId": "1956900_demo"})
c.execute(sql, [t2[k] for k in cols])
tid2 = c.execute("select last_insert_rowid()").fetchone()[0]

c.execute(
    "insert into snapshots (taskId, localEpisodes, localLastEpisode, tmdbLatest, missing, tmdbTotal, addedEpisodes, status, lastSyncAt, lastMessage, lastOk) "
    "values (?,?,?,?,?,?,?,?,?,?,?)",
    (tid1, 12, 12, 0, 0, 0, 0, "LOCAL_LATEST", now - 3600_000, "", 1),
)
c.execute(
    "insert into snapshots (taskId, localEpisodes, localLastEpisode, tmdbLatest, missing, tmdbTotal, addedEpisodes, status, lastSyncAt, lastMessage, lastOk) "
    "values (?,?,?,?,?,?,?,?,?,?,?)",
    (tid2, 9, 9, 0, 0, 0, 3, "LOCAL_ADDED", now, "", 1),
)
c.execute("insert into task_sources (taskId, position, shareUrl, shareId, passcode, createdAt, lastStatus, lastError, lastCheckedAt) values (?,?,?,?,?,?,?,?,?)",
          (tid1, 0, t["shareUrl"], t["shareId"], "", now, "OK", "", now))
c.execute("insert into task_sources (taskId, position, shareUrl, shareId, passcode, createdAt, lastStatus, lastError, lastCheckedAt) values (?,?,?,?,?,?,?,?,?)",
          (tid2, 0, t2["shareUrl"], t2["shareId"], "", now, "OK", "", now))
c.commit()
print("demo tasks", tid1, tid2)
print(c.execute("select taskId,localEpisodes,tmdbTotal,addedEpisodes,status from snapshots").fetchall())
c.close()

