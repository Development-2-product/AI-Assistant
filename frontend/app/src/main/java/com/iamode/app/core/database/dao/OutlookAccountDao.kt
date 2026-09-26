package com.iamode.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.iamode.app.core.database.entity.OutlookAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutlookAccountDao {
    @Query("SELECT * FROM outlook_accounts ORDER BY addedAt") fun observeAll(): Flow<List<OutlookAccountEntity>>
    @Query("SELECT * FROM outlook_accounts") suspend fun all(): List<OutlookAccountEntity>
    @Query("SELECT * FROM outlook_accounts WHERE email = :email LIMIT 1") suspend fun get(email: String): OutlookAccountEntity?
    @Upsert suspend fun upsert(e: OutlookAccountEntity)
    @Query("DELETE FROM outlook_accounts WHERE email = :email") suspend fun delete(email: String)
}
