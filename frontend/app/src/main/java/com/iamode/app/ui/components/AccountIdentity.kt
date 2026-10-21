package com.iamode.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iamode.app.core.database.entity.GmailAccountEntity

/**
 * Shared connected-account identity. Gmail's authorized profile endpoint exposes the email
 * address but not a profile-photo URL, so this deliberately uses a stable initial fallback.
 * A future consented identity source can supply [photoUrl] without changing callers.
 */
@Composable
fun AccountAvatar(
    email: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
    photoUrl: String? = null,
) {
    // Keep the optional value in the public API: no remote image is fabricated or fetched from
    // an email address. A valid, consented URL can be rendered here when the product obtains one.
    val initial = email.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "I"
    Surface(
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .28f)),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.background(Brush.linearGradient(listOf(Color(0xFF167DFF), Color(0xFF7B3CFF)))),
            contentAlignment = Alignment.Center,
        ) {
            // photoUrl is intentionally unused until a consented source and image loader exist.
            Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * .42).sp)
        }
    }
}

@Composable
fun AccountMenu(
    expanded: Boolean,
    activeEmail: String,
    accounts: List<GmailAccountEntity>,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
) {
    var removalCandidate by remember { mutableStateOf<String?>(null) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color(0xFF10243D),
        tonalElevation = 8.dp,
    ) {
        if (accounts.isEmpty()) {
            Column(Modifier.width(300.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar("", size = 58)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("No Gmail account", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Connect an account to organise your mail", color = Color(0xFFB7C8E9), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .12f))
            DropdownMenuItem(
                text = { Text("Add Gmail account", color = Color.White) },
                leadingIcon = { Icon(Icons.Filled.Add, null, tint = Color(0xFF85BCFF)) },
                onClick = { onDismiss(); onAddAccount() },
            )
        } else {
            Column(Modifier.width(300.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(activeEmail, size = 58)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(activeEmail.substringBefore('@').ifBlank { "IA Mode account" }, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(activeEmail, color = Color(0xFFB7C8E9), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Connected", color = Color(0xFF66E2A5), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (accounts.size > 1) {
                HorizontalDivider(color = Color.White.copy(alpha = .12f))
                accounts.filter { it.email != activeEmail }.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AccountAvatar(account.email, size = 36)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(account.email, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (account.needsReauth) "Reconnect required" else "Connected", color = if (account.needsReauth) Color(0xFFFFC46B) else Color(0xFF9FBCE8), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        onClick = { removalCandidate = account.email; onDismiss() },
                    )
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .12f))
            DropdownMenuItem(
                text = { Text("Remove account", color = Color(0xFFFFB4AB)) },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = Color(0xFFFFB4AB)) },
                onClick = { removalCandidate = activeEmail; onDismiss() },
            )
        }
    }
    removalCandidate?.let { email ->
        AlertDialog(
            onDismissRequest = { removalCandidate = null },
            title = { Text("Remove $email?") },
            text = { Text("IA Mode will disconnect this Gmail account and revoke its Gmail permission. Your Gmail mailbox is not deleted.") },
            confirmButton = {
                TextButton(onClick = { removalCandidate = null; onRemoveAccount(email) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removalCandidate = null }) { Text("Cancel") } },
        )
    }
}
