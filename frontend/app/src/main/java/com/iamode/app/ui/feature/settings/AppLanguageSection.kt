package com.iamode.app.ui.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.iamode.app.core.i18n.AppLanguage
import com.iamode.app.core.i18n.I18n
import com.iamode.app.core.i18n.tr
import com.iamode.app.ui.components.SectionTitle
import com.iamode.app.ui.components.SettingRow

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * App language: System default, English, తెలుగు, हिन्दी. Language names are always shown in their own script
 * so anyone can find theirs. Android 13+ applies it system-wide for IA Mode; older versions recreate the screen.
 */
@Composable
fun AppLanguageSection() {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val current = remember { I18n.current(context) }
    Column {
        SectionTitle(tr("App language"))
        Box {
            SettingRow(
                tr("Language"),
                if (current == AppLanguage.SYSTEM) tr("System default") else current.nativeName,
                onClick = { menu = true },
            )
            DropdownMenu(menu, { menu = false }) {
                AppLanguage.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(if (lang == AppLanguage.SYSTEM) tr("System default") else lang.nativeName) },
                        onClick = {
                            menu = false
                            if (lang != current) {
                                I18n.set(context, lang)
                                // Android 13+ recreates the screen itself after the per-app language changes.
                                if (Build.VERSION.SDK_INT < 33) context.findActivity()?.recreate()
                            }
                        },
                    )
                }
            }
        }
        Text(tr("Replies to people are always written in their language, whatever you choose here."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
