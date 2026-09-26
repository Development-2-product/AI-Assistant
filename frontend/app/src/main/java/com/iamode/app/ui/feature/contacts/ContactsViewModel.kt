package com.iamode.app.ui.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(private val contacts: ContactRepository) : ViewModel() {
    val all: StateFlow<List<Contact>> = contacts.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(contact: Contact) = viewModelScope.launch { contacts.upsert(contact) }
    fun delete(id: String) = viewModelScope.launch { contacts.delete(id) }
}
