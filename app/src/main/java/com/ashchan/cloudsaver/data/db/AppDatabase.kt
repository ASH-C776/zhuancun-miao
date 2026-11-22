package com.ashchan.cloudsaver.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地数据库：全部数据只在手机上，没有服务端。
 * 版本 1 起步；后续加字段走 Migration，不做破坏性重建（任务数据是用户手工攒的）。
 */
@Database(
    entities = [TaskEntity::class, SnapshotEntity::class, RunRecordEntity::class],
    version = 1,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
