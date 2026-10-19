package com.iamode.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real SQLite schema upgrades; SQLCipher is supplied by the production database factory. */
@RunWith(AndroidJUnit4::class)
class IAModeDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(IAModeDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration9To11CreatesCommitmentsSchemaForExistingInstallations() {
        helper.createDatabase("commitments-v9", 9).close()
        helper.runMigrationsAndValidate(
            "commitments-v9",
            11,
            true,
            IAModeDatabase.MIGRATION_9_10,
            IAModeDatabase.MIGRATION_10_11,
        )
            .query("SELECT id, sourceMessageId, normalizedAction FROM chat_commitments LIMIT 0")
            .close()
    }

    @Test fun migration11To12PreservesCommitmentsAndAddsPolicyColumns() {
        helper.createDatabase("commitments-v11", 11).close()
        helper.runMigrationsAndValidate("commitments-v11", 12, true, IAModeDatabase.MIGRATION_11_12)
            .query("SELECT autoReplyControl, allowedAutoReplyChannels FROM contacts LIMIT 0")
            .close()
    }
}
