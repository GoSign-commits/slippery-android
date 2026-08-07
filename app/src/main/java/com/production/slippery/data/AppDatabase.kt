package com.production.slippery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DraftTransaction::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftTransactionDao(): DraftTransactionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slippery.db"
                )
                    // No fallbackToDestructiveMigration — any future schema change
                    // MUST add a real Migration, matching Handy Andy's pattern. Room
                    // will throw rather than silently wipe unsynced drafts.
                    .build().also { INSTANCE = it }
            }
        }
    }
}
