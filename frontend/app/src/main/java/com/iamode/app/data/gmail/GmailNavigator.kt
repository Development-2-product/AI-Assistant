package com.iamode.app.data.gmail

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the original thread in Gmail when that app exposes a handler, then falls back to the web. */
object GmailNavigator {
    private const val GMAIL_PACKAGE = "com.google.android.gm"

    fun openOriginal(context: Context, accountEmail: String?, threadId: String?) {
        val thread = threadId?.takeIf { it.isNotBlank() } ?: return
        // Account-aware web URL. Gmail does not publish a stable public native intent for an
        // exact message, so an explicit package is attempted but never relied upon.
        val account = accountEmail?.let(Uri::encode)
        val url = if (account == null) "https://mail.google.com/mail/#all/$thread"
            else "https://mail.google.com/mail/u/$account/#all/$thread"
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val gmail = Intent(web).setPackage(GMAIL_PACKAGE)
        val target = if (gmail.resolveActivity(context.packageManager) != null) gmail else web
        context.startActivity(target)
    }
}
