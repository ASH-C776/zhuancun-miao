import sqlite3

c = sqlite3.connect(r"D:\Doubao\zhuancunmiao\docs\dbpull2\d.db")
print("tasks:")
for r in c.execute("select id, name, driveType, substr(shareUrl,1,32), shareId from tasks"):
    print("  ", r)
print("sources:")
for r in c.execute("select taskId, position, substr(shareUrl,1,32), shareId from task_sources"):
    print("  ", r)
print("snapshots:")
for r in c.execute("select taskId, localEpisodes, localLastEpisode, tmdbLatest, tmdbTotal, status from snapshots"):
    print("  ", r)
