package com.iamode.app.ui.feature.privacy

import com.iamode.app.core.database.dao.AlertDao
import com.iamode.app.core.database.dao.ChatCommitmentDao
import com.iamode.app.core.database.dao.ContactDao
import com.iamode.app.core.database.dao.ConversationDao
import com.iamode.app.core.database.dao.MailActionDao
import com.iamode.app.core.database.dao.MailIntelligenceDao
import com.iamode.app.core.database.dao.MessageDao
import com.iamode.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** Password-encrypted, count-only ownership export. Never serializes message/email bodies or identifiers. */
@Singleton
class RedactedExport @Inject constructor(
    private val settings: SettingsRepository, private val contacts: ContactDao,
    private val conversations: ConversationDao, private val messages: MessageDao,
    private val commitments: ChatCommitmentDao, private val mail: MailIntelligenceDao,
    private val actions: MailActionDao, private val alerts: AlertDao,
) {
    suspend fun create(password: CharArray): ByteArray {
        require(password.size >= 12) { "Use at least 12 characters" }
        val contactRows = contacts.observeAll().first()
        val commitmentRows = commitments.all().first()
        val mailRows = mail.observeAll().first()
        val actionRows = actions.observeAll().first()
        val prefs = settings.current()
        val json = JSONObject().apply {
            put("format", "ia-mode-redacted-export-v1")
            put("createdAt", System.currentTimeMillis())
            put("privacy", "Count-only export. No message bodies, emails, names, addresses, identifiers, tokens, keys, endpoints or source excerpts.")
            put("settings", JSONObject().apply {
                put("mailIntelligence", prefs.mailIntelligence); put("jobReminders", prefs.jobReminders)
                put("celebrations", prefs.celebrations); put("approvalNotifications", prefs.approvalNotifications)
            })
            put("counts", JSONObject().apply {
                put("contacts", contactRows.size); put("conversations", conversations.observeCount().first())
                put("messages", messages.observeCount().first()); put("commitments", commitmentRows.size)
                put("mailIntelligence", mailRows.size); put("mailActions", actionRows.size); put("activity", alerts.observeCount().first())
            })
            put("contactsByRelationship", JSONObject(contactRows.groupingBy { it.relationship }.eachCount()))
            put("commitmentsByStatus", JSONObject(commitmentRows.groupingBy { it.status }.eachCount()))
            put("mailByCategory", JSONObject(mailRows.groupingBy { it.category }.eachCount()))
            put("mailActionsByStatus", JSONObject(actionRows.groupingBy { it.status }.eachCount()))
        }
        return encrypt(json.toString().toByteArray(Charsets.UTF_8), password)
    }

    private fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom(); val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 210_000, 256)).encoded
        password.fill('\u0000')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(8 + salt.size + iv.size + encrypted.size).put("IAMEXP01".toByteArray()).put(salt).put(iv).put(encrypted).array()
    }
}
