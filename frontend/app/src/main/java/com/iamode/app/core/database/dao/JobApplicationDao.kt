package com.iamode.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.iamode.app.core.database.entity.ApplicationEventEntity
import com.iamode.app.core.database.entity.JobApplicationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JobApplicationDao {
    @Upsert suspend fun upsert(e: JobApplicationEntity)
    @Query("SELECT * FROM job_applications WHERE id = :id LIMIT 1") suspend fun get(id: String): JobApplicationEntity?
    @Query("SELECT * FROM job_applications WHERE id = :id LIMIT 1") fun observe(id: String): Flow<JobApplicationEntity?>
    @Query("SELECT * FROM job_applications ORDER BY lastActivityAt DESC") fun observeAll(): Flow<List<JobApplicationEntity>>
    @Query("SELECT * FROM job_applications") suspend fun all(): List<JobApplicationEntity>
    @Query("DELETE FROM job_applications WHERE id = :id") suspend fun delete(id: String)

    /** Returns -1 when the same event (same email + type) was already recorded. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(e: ApplicationEventEntity): Long
    @Query("SELECT * FROM application_events WHERE applicationId = :id ORDER BY at DESC") fun observeEvents(id: String): Flow<List<ApplicationEventEntity>>
    @Query("SELECT applicationId FROM application_events WHERE emailId = :emailId LIMIT 1") suspend fun applicationForEmail(emailId: String): String?
    @Query("DELETE FROM application_events WHERE applicationId = :id") suspend fun deleteEvents(id: String)
}
