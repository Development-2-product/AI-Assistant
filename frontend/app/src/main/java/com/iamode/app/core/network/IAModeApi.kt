package com.iamode.app.core.network

import com.iamode.app.core.network.dto.MissedCallRequest
import com.iamode.app.core.network.dto.ProcessMessageRequest
import com.iamode.app.core.network.dto.ProcessMessageResponse
import com.iamode.app.core.network.dto.RecapRequest
import com.iamode.app.core.network.dto.RecapResponse
import com.iamode.app.core.network.dto.ReplyResponse
import com.iamode.app.core.network.dto.RestyleRequest
import com.iamode.app.core.network.dto.AiCheckResponse
import com.iamode.app.core.network.dto.AuthCheckResponse
import com.iamode.app.core.network.dto.HealthResponse
import com.iamode.app.core.network.dto.MailIntelligenceRequestDto
import com.iamode.app.core.network.dto.MailIntelligenceResponseDto
import com.iamode.app.core.network.dto.ReplyDraftRequestDto
import com.iamode.app.core.network.dto.ReplyDraftResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** IA Mode backend (FastAPI + Gemini). */
interface IAModeApi {
    @POST("v1/messages/process")
    suspend fun process(@Body body: ProcessMessageRequest): ProcessMessageResponse

    @POST("v1/mail/intelligence")
    suspend fun mailIntelligence(@Body body: MailIntelligenceRequestDto): MailIntelligenceResponseDto

    @POST("v1/mail/reply-draft")
    suspend fun mailReplyDraft(@Body body: ReplyDraftRequestDto): ReplyDraftResponseDto

    @POST("v1/messages/restyle")
    suspend fun restyle(@Body body: RestyleRequest): ReplyResponse

    @POST("v1/calls/missed-reply")
    suspend fun missedCallReply(@Body body: MissedCallRequest): ReplyResponse

    @POST("v1/conversations/recap")
    suspend fun recap(@Body body: RecapRequest): RecapResponse

    @GET("v1/health")
    suspend fun health(): HealthResponse

    @GET("v1/auth/check")
    suspend fun authCheck(): AuthCheckResponse

    @POST("v1/diagnostics/ai")
    suspend fun aiCheck(): AiCheckResponse
}
