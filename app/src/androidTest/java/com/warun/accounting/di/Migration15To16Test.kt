package com.warun.accounting.di

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.warun.accounting.data.local.WarunDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration15To16Test {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WarunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationAddsOnlyEmptyElectronicSubmissionHistoryAndPreservesPaperHistory() {
        val databaseName = "migration-15-16-${UUID.randomUUID()}"
        migrationHelper.createDatabase(databaseName, 15).use { database ->
            database.execSQL(
                """
                INSERT INTO monthly_submissions(targetMonth, status, submittedAt, updatedAt)
                VALUES ('2026-06', 'submitted', 100, 101)
                """.trimIndent()
            )
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            16,
            true,
            DatabaseModule.MIGRATION_15_16
        ).use { database ->
            assertEquals(1L, database.count("monthly_submissions"))
            assertEquals(
                "submitted",
                database.stringValue(
                    "SELECT status FROM monthly_submissions WHERE targetMonth = '2026-06'"
                )
            )
            assertEquals(0L, database.count("electronic_submission_records"))
            assertEquals(
                setOf(
                    "index_electronic_submission_records_targetMonth",
                    "index_electronic_submission_records_generatedAt"
                ),
                database.explicitIndexNames("electronic_submission_records")
            )
            assertTrue(database.tableExists("electronic_submission_records"))
        }
    }

    private fun SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringValue(sql: String): String =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.explicitIndexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                val originIndex = cursor.getColumnIndex("origin")
                while (cursor.moveToNext()) {
                    if (cursor.getString(originIndex) == "c") {
                        add(cursor.getString(nameIndex))
                    }
                }
            }
        }

    private fun SupportSQLiteDatabase.tableExists(table: String): Boolean =
        query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() }
}
