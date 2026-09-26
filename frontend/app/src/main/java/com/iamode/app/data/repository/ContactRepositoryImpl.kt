package com.iamode.app.data.repository

import com.iamode.app.core.database.dao.ContactDao
import com.iamode.app.core.database.toDomain
import com.iamode.app.core.database.toEntity
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.repository.ContactRepository
import com.iamode.app.domain.util.PhoneNumbers
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(private val dao: ContactDao) : ContactRepository {
    override fun observeAll() = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun findByPhone(phone: String) = dao.byPhone(PhoneNumbers.normalize(phone))?.toDomain()
    override suspend fun findByEmail(email: String) = dao.byEmail(email.lowercase())?.toDomain()
    override suspend fun findByName(name: String) = dao.byName(name.trim())?.toDomain()
    override suspend fun upsert(contact: Contact) =
        dao.upsert(contact.copy(phone = contact.phone?.let(PhoneNumbers::normalize)).toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
}
