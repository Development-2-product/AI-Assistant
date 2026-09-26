package com.iamode.app.data.gmail

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable data class MessageRef(val id: String, val threadId: String = "")
@Serializable data class ListMessagesResponse(val messages: List<MessageRef> = emptyList(), val nextPageToken: String? = null)
@Serializable data class GmailHeader(val name: String, val value: String)
@Serializable data class PartBody(val data: String? = null, val size: Int = 0)
@Serializable data class MessagePart(
    val mimeType: String = "",
    val headers: List<GmailHeader> = emptyList(),
    val body: PartBody? = null,
    val parts: List<MessagePart> = emptyList(),
)
@Serializable data class GmailMessage(
    val id: String,
    val threadId: String = "",
    val labelIds: List<String> = emptyList(),
    val snippet: String = "",
    val internalDate: String? = null,
    val payload: MessagePart? = null,
)
@Serializable data class GmailThread(val id: String, val messages: List<GmailMessage> = emptyList())
@Serializable data class SendRequest(val raw: String, val threadId: String? = null)
@Serializable data class GmailProfile(val emailAddress: String)
@Serializable data class ModifyRequest(val addLabelIds: List<String> = emptyList(), val removeLabelIds: List<String> = emptyList())
@Serializable data class GmailLabel(val id: String, val name: String, val type: String = "user")
@Serializable data class LabelsResponse(val labels: List<GmailLabel> = emptyList())
@Serializable data class CreateLabelRequest(
    val name: String,
    val labelListVisibility: String = "labelShow",
    val messageListVisibility: String = "show",
)

/** Gmail REST API. Access tokens come from Google Identity authorization, per account. */
interface GmailApi {
    @GET("gmail/v1/users/me/profile")
    suspend fun profile(@Header("Authorization") auth: String): GmailProfile

    @GET("gmail/v1/users/me/messages")
    suspend fun list(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("maxResults") maxResults: Int = 20,
    ): ListMessagesResponse

    @GET("gmail/v1/users/me/messages/{id}")
    suspend fun get(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("format") format: String = "full",
    ): GmailMessage

    @GET("gmail/v1/users/me/threads/{id}")
    suspend fun thread(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("format") format: String = "full",
    ): GmailThread

    /** Label changes (archive = remove INBOX, mark read = remove UNREAD, move = add label + remove INBOX). */
    @POST("gmail/v1/users/me/messages/{id}/modify")
    suspend fun modify(@Header("Authorization") auth: String, @Path("id") id: String, @Body body: ModifyRequest): MessageRef

    @GET("gmail/v1/users/me/labels")
    suspend fun labels(@Header("Authorization") auth: String): LabelsResponse

    @POST("gmail/v1/users/me/labels")
    suspend fun createLabel(@Header("Authorization") auth: String, @Body body: CreateLabelRequest): GmailLabel

    @POST("gmail/v1/users/me/messages/send")
    suspend fun send(@Header("Authorization") auth: String, @Body body: SendRequest): MessageRef
}
