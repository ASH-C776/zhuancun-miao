package com.ashchan.cloudsaver.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地数据库：全部数据只在手机上，没有服务端。
 * 版本 1 起步；后续加字段走 Migration，不做破坏性重建（任务数据是用户手工攒的）。
 */
@Database(
    entities = [TaskEntity::class, SnapshotEntity::class, RunRecordEntity::class, TaskSourceEntity::class],
    version = 17,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun runRecordDao(): RunRecordDao
    abstract fun taskSourceDao(): TaskSourceDao

    companion object {
        private const val DB_NAME = "zhuancunmiao.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v2：run_records 加 `taskName`。
         *
         * 原因：运行日志只存了 taskId，日志页看不出「这条是哪部剧」；
         * 任务被删掉后更是彻底对不上号。任务名是执行那一刻的快照，改名后旧记录保留旧名，符合日志语义。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE run_records ADD COLUMN taskName TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v3：snapshots 加 `lastOk`。
         *
         * 卡片要能把「上次同步失败」和「上次同步成功但有提醒」区分开（红点 vs 灰字），
         * 光有 lastMessage 做不到。老数据默认按成功处理，不误报。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE snapshots ADD COLUMN lastOk INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * v4：tasks 加 `ruleKey`（任务级命名规则）。
         *
         * 老任务默认空串 = 不改名，与 Stage 4 之后的实际行为（renamePlan 恒为空）一致，不改变已有结果。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN ruleKey TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v5：tasks 加 `seasonNo`（目标侧季度编号）。
         *
         * 默认 0 = 不建季文件夹（剧名/视频平铺），老任务保持原来的单层结构不动。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN seasonNo INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v6：tasks 加 `folderWithTmdbId`（网盘文件夹名是否带 TMDB ID）。
         *
         * 默认 false = 只用剧名当文件夹名，老任务不受影响。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN folderWithTmdbId INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v7：snapshots 加 `tmdbTotal`（TMDB 的总集数）。
         *
         * 卡片要区分「追平当前最新集」和「整部剧收齐了」：只有总集数到手，
         * 才能判断 30 集全齐 → 显示「转存完」，用户据此自己决定删不删任务。
         * 老快照默认 0 = 未知，不改判断口径。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE snapshots ADD COLUMN tmdbTotal INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v8：新增 `task_sources`（多源）。
         *
         * 一个任务可以带多个分享链接。只加表、不动 tasks 结构：
         * 老任务的 shareUrl/shareId/passcode 原样搬成 `position = 0` 的那一行，
         * 引擎与表单从此读 task_sources，行为与改前完全一致（单源）。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_sources` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`shareUrl` TEXT NOT NULL, " +
                        "`shareId` TEXT NOT NULL, " +
                        "`passcode` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_sources_taskId` " +
                        "ON `task_sources` (`taskId`)"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `task_sources` " +
                        "(`taskId`, `position`, `shareUrl`, `shareId`, `passcode`, `createdAt`) " +
                        "SELECT `id`, 0, `shareUrl`, `shareId`, `passcode`, `createdAt` " +
                        "FROM `tasks` WHERE `shareUrl` <> ''"
                )
            }
        }

        /**
         * v9：`task_sources` 加源状态三列（lastStatus / lastError / lastCheckedAt）。
         *
         * 目的：多源时能直接看出「哪一个链接失效了」，而不是整张卡片只写一句失败。
         * 老数据默认 UNKNOWN = 没检测过，不误报；口径是「一次失败算 WARN、连续两次才 FAIL」，
         * 免得网络抖动把源标成失效。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE task_sources ADD COLUMN lastStatus TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                db.execSQL(
                    "ALTER TABLE task_sources ADD COLUMN lastError TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE task_sources ADD COLUMN lastCheckedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v10：`snapshots` 加 `addedEpisodes`（本轮新增集数）。
         *
         * 目的：没绑 TMDB 的任务没有「最新集」可比，以前一律判 UNKNOWN → 卡片永远显示「未同步」，
         * 明明盘里已经是最新的。加上这一列后，这类任务能区分「这轮转进来 N 集」与「没有新集」。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE snapshots ADD COLUMN addedEpisodes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v11：`tasks.seasonNo` 语义更正 —— 0 不再是「平铺」，而是**特别版 / Season 0**。
         *
         * 用户口径：`0` 在季号里是真实存在的一季（TMDB 的 Season 0 = 特别篇），
         * 「没选季度」必须是另一个值，否则会把特别篇平铺进剧名目录、也筛不出 Season 0。
         * 现在 `SEASON_FLAT = -2`、`SEASON_SPECIALS = 0`、`SEASON_MULTI = -1`。
         *
         * 迁移：老库里 0 全是「平铺」语义（当时根本表达不了特别版），所以统一改成 -2。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE tasks SET seasonNo = -2 WHERE seasonNo = 0")
            }
        }

        /**
         * v12：tasks 加 `pinned`（卡片置顶）。
         *
         * 老任务默认 false = 不置顶，列表顺序与改前一致（仍按 id 升序）。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v13：tasks 加 `posterPath`（TMDB 海报路径）。
         *
         * 只存相对路径，图片按 tmdbId 缓存在 filesDir/posters；老任务默认空串 = 无海报。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN posterPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v14：tasks 加自定义分季的两个数（起始集 / 本季集数）。
         *
         * 默认 0 = 不启用，仍按 TMDB 的季集数算；只有 TMDB 合并了多篇的剧才需要填。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN seasonStartEp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN seasonEpCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v15：两列是 0.14.0「集数口径」弹窗留下的，该功能已移除。
         *
         * 迁移与列都保留，只为兼容已经升到 v15 的设备数据库；业务代码不再读取。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN episodeMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN customLatestEp INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v16：开始保存的起点，默认 1 季 1 集 = 和以前完全一样。 */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN saveStartSeason INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN saveStartEpisode INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v17：首页卡片「最近有更新」排序用的 lastNewAt。
         *
         * 顺手把历史回填一次：run_records 里每一轮都记了 restoredFiles，
         * 取每个任务「最近一次真的转进新集」的开始时间即可，不用从 0 开始慢慢攒。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE snapshots ADD COLUMN lastNewAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE snapshots SET lastNewAt = COALESCE((" +
                        "SELECT MAX(startedAt) FROM run_records " +
                        "WHERE run_records.taskId = snapshots.taskId AND run_records.restoredFiles > 0" +
                        "), 0)"
                )
            }
        }

        /** 全部迁移，按版本顺序排列（单测会校验 1→16 不能断档） */
        val MIGRATIONS: List<Migration> = listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
        )

        /** 当前 schema 版本，供单测比对（版本号在 [Database] 注解上，改这里只是镜像） */
        const val SCHEMA_VERSION: Int = 17

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(*MIGRATIONS.toTypedArray()).build().also { instance = it }
            }
    }
}




