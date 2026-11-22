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
    version = 2,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
