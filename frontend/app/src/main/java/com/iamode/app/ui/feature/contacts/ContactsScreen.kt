package com.iamode.app.ui.feature.contacts

import com.iamode.app.core.i18n.tr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iamode.app.domain.model.Contact
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.Script
import com.iamode.app.ui.components.Avatar
import com.iamode.app.ui.components.ChipRow
import com.iamode.app.ui.components.readableWidth
import com.iamode.app.ui.components.EmptyState
import com.iamode.app.ui.components.RelationshipPill
import com.iamode.app.ui.theme.IAColors
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onBack: () -> Unit, vm: ContactsViewModel = hiltViewModel()) {
    val contacts by vm.all.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Contact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(tr("Contacts")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                editing = Contact(UUID.randomUUID().toString(), "", relationship = Relationship.FRIEND)
            }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(tr("Add contact")) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            Text(
                tr("Label people so IA Mode knows how to treat them. Clients and business get automatic replies; ") +
                    tr("partner, friends and family need your approval. Saved contacts without a label count as friends, ") +
                    tr("and numbers not in your phone count as unknown."),
                style = MaterialTheme.typography.bodyMedium, color = IAColors.Grey, modifier = Modifier.padding(16.dp),
            )
            if (contacts.isEmpty()) EmptyState(tr("No labelled contacts"), tr("Add your clients, partner and family."))
            LazyColumn {
                items(contacts, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.displayName) },
                        supportingContent = {
                            Text(listOfNotNull(c.phone?.let { "+$it" }, c.email,
                                c.language?.let { l -> "${l.label} (${c.script.label.lowercase()})" } ?: tr("Language: auto"))
                                .joinToString(" · "))
                        },
                        leadingContent = { Avatar(c.displayName, c.relationship) },
                        trailingContent = { RelationshipPill(c.relationship) },
                        modifier = Modifier.animateItem().clickable { editing = c },
                    )
                    HorizontalDivider(Modifier.animateItem())
                }
            }
        }
    }

    editing?.let { c ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            ContactEditor(c, onSave = { vm.save(it); editing = null }, onDelete = { vm.delete(c.id); editing = null })
        }
    }
}

@Composable
private fun ContactEditor(initial: Contact, onSave: (Contact) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.displayName) }
    var phone by remember { mutableStateOf(initial.phone.orEmpty()) }
    var email by remember { mutableStateOf(initial.email.orEmpty()) }
    var relationship by remember { mutableStateOf(initial.relationship) }
    var language by remember { mutableStateOf(initial.language) }
    var script by remember { mutableStateOf(initial.script) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let { readContact(context, it) }?.let { (n, p) -> name = n; if (p != null) phone = p }
    }

    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (initial.displayName.isBlank()) tr("Add contact") else tr("Edit contact"), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { picker.launch(null) }) { Text(tr("Pick from phonebook")) }
        OutlinedTextField(name, { name = it }, label = { Text(tr("Name (as saved in WhatsApp)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text(tr("Phone number")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text(tr("Email (for Gmail)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text(tr("Relationship"), style = MaterialTheme.typography.bodyMedium)
        ChipRow {
            Relationship.assignable.forEach { r -> FilterChip(relationship == r, { relationship = r }, { Text(tr(r.label)) }) }
        }
        Text(tr("Language for missed-call texts"), style = MaterialTheme.typography.bodyMedium)
        ChipRow {
            FilterChip(language == null, { language = null }, { Text(tr("Auto")) })
            LanguageCode.entries.forEach { l -> FilterChip(language == l, { language = l }, { Text(tr(l.label)) }) }
        }
        AnimatedVisibility(visible = language != null && language != LanguageCode.EN) {
            ChipRow {
                Script.entries.forEach { s -> FilterChip(script == s, { script = s }, { Text(tr(s.label)) }) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(initial.copy(displayName = name.trim(), phone = phone.trim().ifBlank { null },
                        email = email.trim().lowercase().ifBlank { null }, relationship = relationship,
                        language = language, script = script))
                },
                enabled = name.isNotBlank(),
            ) { Text(tr("Save")) }
            if (initial.displayName.isNotBlank()) TextButton(onClick = onDelete) { Text(tr("Remove")) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Reads name and first phone number of a picked contact. */
private fun readContact(context: Context, uri: Uri): Pair<String, String?>? {
    val resolver = context.contentResolver
    var id: String? = null
    var name: String? = null
    resolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) { id = it.getString(0); name = it.getString(1) } }
    val displayName = name ?: return null
    val contactId = id ?: return displayName to null
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return displayName to null
    }
    val phone = resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId), null,
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
    return displayName to phone
}
