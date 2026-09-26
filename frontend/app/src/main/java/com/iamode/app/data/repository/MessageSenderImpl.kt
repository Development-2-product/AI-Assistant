package com.iamode.app.data.repository

import com.iamode.app.core.diagnostics.DiagnosticsLog
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.Conversation
import com.iamode.app.domain.model.SendResult
import com.iamode.app.domain.repository.MessageSender
import com.iamode.app.service.notification.ChatReplySender
import com.iamode.app.service.sms.SmsSender
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageSenderImpl @Inject constructor(
    private val chats: ChatReplySender,
    private val sms: SmsSender,
    private val log: DiagnosticsLog,
) : MessageSender {
    override suspend fun send(conversation: Conversation, text: String): SendResult = dispatch(conversation, text).also { r ->
        if (r is SendResult.Failed) log.record("Send", false, "${conversation.channel.label}: ${r.reason}")
    }

    private suspend fun dispatch(conversation: Conversation, text: String): SendResult = when (conversation.channel) {
        Channel.WHATSAPP, Channel.WHATSAPP_BUSINESS, Channel.TELEGRAM, Channel.INSTAGRAM ->
            chats.send(conversation.channel, conversation.address, text)
        // Gmail is intentionally approval-first in the dedicated mail workflow. This blocks
        // any legacy stored conversation from bypassing document/calendar/reply review.
        Channel.GMAIL -> SendResult.Failed("Email replies must be sent from the email review screen")
        Channel.SMS -> sms.send(conversation.address, text)
    }
}
