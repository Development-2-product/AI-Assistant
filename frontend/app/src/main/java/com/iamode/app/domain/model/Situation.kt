package com.iamode.app.domain.model

data class Situation(
    val status: SituationStatus,
    val reason: String,
    val manual: Boolean = false,
)
