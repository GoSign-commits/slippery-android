package com.production.slippery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DraftTransaction::class],
    version = 3,
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

        // v2 -> v3: envelopeId (2026-08-08) — envelope awareness. Any
        // existing local drafts pre-date envelopes existing as a concept
        // at all (this is single-user testing, no real data at stake) —
        // backfilled to '' rather than treated as a blocker. A real
        // envelope-less draft shouldn't be possible going forward; the
        // ViewModel always supplies a real envelopeId on insert from here on.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE draft_transactions ADD COLUMN envelopeId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "slippery.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // No fallbackToDestructiveMigration — any future schema change
                    // MUST add a real Migration, matching Handy Andy's pattern. Room
                    // will throw rather than silently wipe unsynced drafts.
                    .build().also { INSTANCE = it }
            }
        }
    }
}
