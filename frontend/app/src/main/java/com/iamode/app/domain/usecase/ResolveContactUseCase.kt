package com.iamode.app.domain.usecase

import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.UserSettings
import com.iamode.app.domain.repository.ContactRepository
import com.iamode.app.domain.repository.Phonebook
import com.iamode.app.domain.util.PhoneNumbers
import javax.inject.Inject

data class ResolvedContact(val displayName: String, val relationship: Relationship, val contact: Contact?)

/**
 * Relationship always comes from labels the user set, never from an AI guess.
 * Saved phone contacts without a label get the user's default; everyone else is UNKNOWN.
 */
class ResolveContactUseCase @Inject constructor(
    private val contacts: ContactRepository,
    private val phonebook: Phonebook,
) {
    suspend operator fun invoke(channel: Channel, address: String, displayName: String, settings: UserSettings): ResolvedContact {
        val labelled = when (channel) {
            Channel.GMAIL -> contacts.findByEmail(address.lowercase())
            Channel.SMS -> contacts.findByPhone(PhoneNumbers.normalize(address))
            Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS, Channel.TELEGRAM, Channel.INSTAGRAM -> if (PhoneNumbers.looksLikeNumber(address)) contacts.findByPhone(PhoneNumbers.normalize(address))
            else contacts.findByName(displayName)
        }
        if (labelled != null) return ResolvedContact(labelled.displayName, labelled.relationship, labelled)

        val savedName: String? = when (channel) {
            Channel.SMS -> phonebook.nameForNumber(address)
            // Chat apps show the saved contact name; a raw number means the sender isn't saved.
            Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS, Channel.TELEGRAM -> if (PhoneNumbers.looksLikeNumber(address)) phonebook.nameForNumber(address) else displayName
            // Instagram names are usernames, not phone contacts.
            Channel.INSTAGRAM, Channel.GMAIL -> null
        }
        return if (savedName != null) ResolvedContact(savedName, settings.defaultSavedContactRelationship, null)
        else ResolvedContact(displayName, Relationship.UNKNOWN, null)
    }
}
