package com.production.slippery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DraftTransaction::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftTransactionDao(): DraftTransactionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // v1 -> v2: cover-sheet fields (supplier, VAT, slip_number).
        // Real migration, not a destructive wipe — matches Handy Andy's
        // pattern and this repo's own "no fallbackToDestructiveMigration" rule.
        // NOT NULL columns need a DEFAULT for SQLite to backfill existing rows;
        // vatAmount/amountExclVat stay nullable, matching the server-side
        // "null when not VATable, never 0" rule.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE draft_transactions ADD COLUMN supplier TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE draft_transactions ADD COLUMN vatApplicable INTEGER")
                db.execSQL("ALTER TABLE draft_transactions ADD COLUMN vatAmount REAL")
                db.execSQL("ALTER TABLE draft_transactions ADD COLUMN amountExclVat REAL")
                db.execSQL("ALTER TABLE draft_transactions ADD COLUMN slipNumber INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slippery.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // No fallbackToDestructiveMigration — any future schema change
                    // MUST add a real Migration, matching Handy Andy's pattern. Room
                    // will throw rather than silently wipe unsynced drafts.
                    .build().also { INSTANCE = it }
            }
        }
    }
}
