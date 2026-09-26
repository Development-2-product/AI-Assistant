package com.iamode.app.core.notifications

import com.iamode.app.core.i18n.tr

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.iamode.app.MainActivity
import com.iamode.app.R
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.SessionSummary
import com.iamode.app.domain.repository.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotifier @Inject constructor(@ApplicationContext private val context: Context) : Notifier {

    private val nm = NotificationManagerCompat.from(context)

    override fun approvalNeeded(conversation: Conversation, incomingText: String) {
        val reply = conversation.pendingReply.orEmpty()
        val body = buildString {
            append(incomingText)
            if (reply.isNotBlank()) append("\n\nSuggested reply: ").append(reply)
            conversation.reason?.let { append("\n\n").append(it) }
        }
        val builder = base(NotificationChannels.APPROVALS, conversation)
            .setContentTitle("${conversation.displayName} · ${conversation.channel.label}")
            .setContentText(if (reply.isNotBlank()) tr("Suggested: %1\$s", reply) else incomingText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (reply.isNotBlank()) {
            builder.addAction(0, tr("Approve and send"), actionIntent(NotificationActionReceiver.ACTION_APPROVE, conversation.id))
        }
        builder.addAction(replyAction(conversation.id))
        builder.addAction(0, tr("Don't reply"), actionIntent(NotificationActionReceiver.ACTION_REJECT, conversation.id))
        post(approvalId(conversation.id), builder)
    }

    override fun cancel(conversationId: String) = nm.cancel(approvalId(conversationId))

    override fun replySent(conversation: Conversation, text: String, auto: Boolean) {
        val title = when (conversation.channel) {
            Channel.GMAIL -> tr("Replied to %1\$s by email", (conversation.displayName))
            Channel.SMS -> tr("Texted %1\$s after missed call", (conversation.displayName))
            Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS, Channel.TELEGRAM, Channel.INSTAGRAM ->
                tr("Reply action handed to %1\$s on %2\$s", (conversation.displayName), (conversation.channel.label))
        }
        post(
            sentId(conversation.id),
            base(NotificationChannels.ACTIVITY, conversation)
                .setContentTitle(if (auto) "$title automatically" else title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT),
        )
    }

    override fun crisis(conversation: Conversation) = post(
        approvalId(conversation.id),
        base(NotificationChannels.URGENT, conversation)
            .setContentTitle(tr("%1\$s may need you", (conversation.displayName)))
            .setContentText(tr("Their message sounds serious. IA Mode did not reply. Consider calling them."))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                tr("Their message sounds serious. IA Mode did not reply. Consider calling them now. ") +
                    tr("If they may be in immediate danger, contact emergency services (112), or share Tele-MANAS (14416), ") +
                    tr("India's free 24/7 mental-health helpline.")))
            .setPriority(NotificationCompat.PRIORITY_MAX),
    )

    override fun followUp(conversation: Conversation, text: String) = post(
        ("follow" + conversation.id).hashCode(),
        base(NotificationChannels.ACTIVITY, conversation)
            .setContentTitle(tr("Follow up with %1\$s", (conversation.displayName)))
            .setContentText(tr("They asked about money or a commitment. IA Mode replied without agreeing to anything.")),
    )

    override fun sendFailed(conversation: Conversation, reason: String) = post(
        approvalId(conversation.id),
        base(NotificationChannels.APPROVALS, conversation)
            .setContentTitle(tr("Couldn't send to %1\$s", (conversation.displayName)))
            .setContentText(reason),
    )

    override fun gmailReauthNeeded(email: String) = post(
        ("gmail$email").hashCode(),
        NotificationCompat.Builder(context, NotificationChannels.ACCOUNT)
            .setSmallIcon(R.drawable.ic_stat_iamode)
            .setContentTitle(tr("Reconnect %1\$s", email))
            .setContentText(tr("IA Mode lost access to this Gmail account. Open Settings to reconnect."))
            .setContentIntent(openApp(null, email.hashCode()))
            .setAutoCancel(true),
    )

    fun outlookReauthNeeded(email: String) = post(
        ("outlook$email").hashCode(),
        NotificationCompat.Builder(context, NotificationChannels.ACCOUNT)
            .setSmallIcon(R.drawable.ic_stat_iamode)
            .setContentTitle(tr("Reconnect %1\$s", email))
            .setContentText(tr("IA Mode lost access to this Outlook account. Open Settings to reconnect."))
            .setContentIntent(openApp(null, email.hashCode()))
            .setAutoCancel(true),
    )

    override fun sessionSummary(summary: SessionSummary) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, summary.session.id)
        }
        val body = buildString {
            append(summary.headline).append('.')
            if (summary.needsYou.isNotEmpty()) append("\nNeeds you: ").append(summary.needsYou.joinToString { it.displayName })
            if (summary.followUps.isNotEmpty()) append("\nFollow up: ").append(summary.followUps.joinToString { it.displayName })
        }
        post(
            SUMMARY_ID,
            NotificationCompat.Builder(context, NotificationChannels.ACTIVITY)
                .setSmallIcon(R.drawable.ic_stat_iamode)
                .setContentTitle(tr("While you were busy"))
                .setContentText(summary.headline)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntent.getActivity(context, SUMMARY_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true),
        )
    }

    /** Job tracker nudge; tapping opens the application. */
    fun jobReminder(applicationId: String, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_APPLICATION_ID, applicationId)
        }
        val id = ("job$applicationId").hashCode()
        post(
            id,
            NotificationCompat.Builder(context, NotificationChannels.ACTIVITY)
                .setSmallIcon(R.drawable.ic_stat_iamode)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true),
        )
    }

    /** Big news arrived while the app was closed. Opening it plays the celebration once. */
    fun celebration(emailId: String, headline: String, subtitle: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_EMAIL_ID, emailId)
        }
        post(
            ("celebrate$emailId").hashCode(),
            NotificationCompat.Builder(context, NotificationChannels.ACTIVITY)
                .setSmallIcon(R.drawable.ic_stat_iamode)
                .setContentTitle("🎉 $headline")
                .setContentText(subtitle?.replace("\n", " · ") ?: tr("Open IA Mode to see the details"))
                .setContentIntent(PendingIntent.getActivity(context, ("celebrate$emailId").hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true),
        )
    }

    /** Silent, ongoing reminder with a live timer and a Turn off button. */
    fun showModeOn(startedAt: Long, autoReason: String? = null) = post(
        STATUS_ID,
        NotificationCompat.Builder(context, NotificationChannels.STATUS)
            .setSmallIcon(R.drawable.ic_stat_iamode)
            .setContentTitle(tr("IA Mode is on"))
            .setContentText(autoReason?.let { tr("Turned on automatically. %1\$s", it) } ?: tr("Replying to messages and missed calls for you"))
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(null, STATUS_ID))
            .addAction(0, tr("Turn off"), PendingIntent.getBroadcast(context, STATUS_ID,
                Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_TURN_OFF),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)),
    )

    fun hideModeOn() = nm.cancel(STATUS_ID)

    // ---------- helpers ----------

    private fun base(channel: String, c: Conversation) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_iamode)
        .setContentIntent(openApp(c.id, c.id.hashCode()))
        .setAutoCancel(true)
        .setOnlyAlertOnce(false)

    private fun openApp(conversationId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            conversationId?.let { putExtra(MainActivity.EXTRA_CONVERSATION_ID, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, conversationId: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId)
        }
        val mutability = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutability
        return PendingIntent.getBroadcast(context, (action + conversationId).hashCode(), intent, flags)
    }

    private fun replyAction(conversationId: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT)
            .setLabel(tr("Write your reply"))
            .build()
        // RemoteInput needs a mutable PendingIntent so the system can attach the typed text.
        return NotificationCompat.Action.Builder(0, tr("Write reply"),
            actionIntent(NotificationActionReceiver.ACTION_REPLY, conversationId, mutable = true))
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, builder: NotificationCompat.Builder) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) nm.notify(id, builder.build())
    }

    private fun approvalId(id: String) = ("approval$id").hashCode()

    private companion object {
        const val STATUS_ID = 4242
        const val SUMMARY_ID = 4243
    }
    private fun sentId(id: String) = ("sent$id").hashCode()
}
