import sqlite3, shutil, os, time

src = r"D:\Doubao\zhuancunmiao\docs\dbpull\d.db"
demo = r"D:\Doubao\zhuancunmiao\docs\dbpull\demo.db"
shutil.copyfile(src, demo)
c = sqlite3.connect(demo)
now = int(time.time() * 1000)
c.execute(
    "insert or replace into tasks (id,name,driveType,shareUrl,shareId,passcode,saveFid,savePathLabel,"
    "seasonFid,seasonName,startFid,scopeMode,tmdbId,tmdbMediaType,updateMode,weekdays,airTime,enabled,"
    "createdAt,updatedAt,ruleKey,seasonNo,folderWithTmdbId) values "
    "(999,'演示用·恋爱博士','GUANGYA','https://www.guangyapan.com/s/demo','demo','','0','网盘根目录',"
    "'','','0','DIRECT_ONLY','307106','tv','tmdb','周一、周二','',1,?,?,'default',1,0)",
    (now, now),
)
c.execute(
    "insert or replace into snapshots (taskId,localEpisodes,localLastEpisode,tmdbLatest,missing,status,"
    "lastSyncAt,lastMessage,lastOk,tmdbTotal) values (999,10,10,20,10,'BEHIND',?,'',1,30)",
    (now,),
)
c.commit()
print("tasks:", c.execute("select id,name from tasks").fetchall())
print("snap:", c.execute("select taskId,localEpisodes,tmdbLatest,tmdbTotal,status from snapshots").fetchall())
c.close()
