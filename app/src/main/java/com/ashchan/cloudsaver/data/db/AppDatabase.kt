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
    entities = [TaskEntity::class, SnapshotEntity::class, RunRecordEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun runRecordDao(): RunRecordDao

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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                ).build().also { instance = it }
            }
    }
}
