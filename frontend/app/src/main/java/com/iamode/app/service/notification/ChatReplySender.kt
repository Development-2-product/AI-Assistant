package com.iamode.app.service.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.iamode.app.domain.model.Channel
import com.iamode.app.domain.model.SendResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replies through the chat app's own notification Reply button (RemoteInput), the same way
 * smartwatches do. Works only while that chat has a notification.
 */
@Singleton
class ChatReplySender @Inject constructor(@ApplicationContext private val context: Context) {

    private val actions = ConcurrentHashMap<String, Notification.Action>()
    // These mappings exist only while this process is alive. They let us drop a reply action as
    // soon as Android removes the notification that supplied it.
    private val notificationActions = ConcurrentHashMap<String, String>()
    private val actionOwners = ConcurrentHashMap<String, String>()
    private val recentlySent = ConcurrentHashMap<String, ArrayDeque<String>>()

    private fun key(channel: Channel, chat: String) = "${channel.name}:$chat"

    fun register(channel: Channel, chat: String, action: Notification.Action, notificationKey: String? = null) {
        val actionKey = key(channel, chat)
        actions[actionKey] = action
        notificationKey?.let {
            notificationActions[it] = actionKey
            actionOwners[actionKey] = it
        }
    }

    /** Restores only actions Android still exposes; PendingIntent values are never persisted. */
    fun registerActive(notificationKey: String, chat: ParsedChat) {
        // A notification update can remove its reply action. Treat it as unavailable immediately.
        unregisterNotification(notificationKey)
        val action = chat.replyAction
        if (action == null) {
            removeAction(key(chat.channel, chat.chat))
            return
        }
        register(chat.channel, chat.chat, action, notificationKey)
    }

    /** Removes an action only if this notification still owns the current action for its chat. */
    fun unregisterNotification(notificationKey: String) {
        val actionKey = notificationActions.remove(notificationKey) ?: return
        if (actionOwners.remove(actionKey, notificationKey)) removeAction(actionKey)
    }

    /** A disconnected listener must not keep using actions observed before the disconnect. */
    fun clearActiveActions() {
        actions.clear()
        notificationActions.clear()
        actionOwners.clear()
    }

    private fun removeAction(actionKey: String) {
        actions.remove(actionKey)
        actionOwners.remove(actionKey)?.let { owner -> notificationActions.remove(owner, actionKey) }
    }

    /** True if [text] is an echo of something IA Mode sent (not typed by the user). */
    fun wasSentByUs(channel: Channel, chat: String, text: String): Boolean =
        recentlySent[key(channel, chat)]?.any { it.trim() == text.trim() } == true

    fun send(channel: Channel, chat: String, text: String): SendResult {
        val k = key(channel, chat)
        val action = actions[k]
            ?: return SendResult.Failed("The ${channel.label} chat notification is no longer active. Open ${channel.label} and reply manually; keep its chat notification active until IA Mode sends.")
        val inputs = action.remoteInputs ?: return SendResult.Failed("${channel.label} no longer offers a reply action. Open ${channel.label} and reply manually; keep its chat notification active until IA Mode sends.")
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            recentlySent.getOrPut(k) { ArrayDeque() }.apply {
                addLast(text)
                while (size > 10) removeFirst()
            }
            SendResult.Sent
        } catch (e: PendingIntent.CanceledException) {
            removeAction(k)
            SendResult.Failed("The ${channel.label} chat notification expired. Open ${channel.label} and reply manually; keep its chat notification active until IA Mode sends.")
        }
    }
}
