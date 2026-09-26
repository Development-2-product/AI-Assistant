package com.iamode.app.domain.util

import com.iamode.app.domain.model.Gender
import com.iamode.app.domain.model.Language
import com.iamode.app.domain.model.LanguageCode
import com.iamode.app.domain.model.Relationship
import com.iamode.app.domain.model.Script
import com.iamode.app.domain.model.SituationStatus

/** Used only when the AI backend is unreachable, so callers still get a sensible message. */
object OfflineTemplates {

    fun missedCall(language: Language, status: SituationStatus, relationship: Relationship, gender: Gender): String {
        if (relationship.isProfessional || relationship == Relationship.UNKNOWN) {
            val what = when (status) {
                SituationStatus.DRIVING -> "I'm driving at the moment"
                SituationStatus.RIDING -> "I'm on the road at the moment"
                SituationStatus.INTERVIEW -> "I'm in an interview right now"
                SituationStatus.MEETING -> "I'm in a meeting at the moment"
                else -> "I'm unavailable at the moment"
            }
            return "Hi, sorry I missed your call. $what. I'll call you back shortly."
        }
        val text = if (language.code == LanguageCode.TE && language.script == Script.NATIVE) {
            teluguNative(status)
        } else {
            roman(language.code, status, gender)
        }
        return if (relationship == Relationship.PARTNER) "$text ❤️" else text
    }

    private fun teluguNative(s: SituationStatus) = when (s) {
        SituationStatus.DRIVING -> "డ్రైవ్ చేస్తున్నా, కాల్ లిఫ్ట్ చేయలేను. ఆపాక కాల్ చేస్తా."
        SituationStatus.RIDING -> "బైక్ మీద ఉన్నా, తర్వాత కాల్ చేస్తా."
        SituationStatus.INTERVIEW -> "ఇంటర్వ్యూలో ఉన్నా, తర్వాత కాల్ చేస్తా."
        SituationStatus.MEETING -> "మీటింగ్‌లో ఉన్నా, కొంచెం సేపట్లో కాల్ చేస్తా."
        SituationStatus.GAMING -> "గేమ్ ఆడుతున్నా 🎮 కొంచెం సేపట్లో కాల్ చేస్తా."
        SituationStatus.SLEEPING -> "పడుకున్నా 😴 లేచాక కాల్ చేస్తా."
        SituationStatus.BUSY -> "కొంచెం బిజీగా ఉన్నా, తర్వాత కాల్ చేస్తా."
        SituationStatus.AVAILABLE -> "నీ కాల్ మిస్ అయ్యింది, కొంచెం సేపట్లో కాల్ చేస్తా."
    }

    private fun roman(code: LanguageCode, s: SituationStatus, gender: Gender): String {
        val f = gender == Gender.FEMALE
        val hr = if (f) "rahi" else "raha"
        val ht = if (f) "karti" else "karta"
        return when (code) {
            LanguageCode.TE -> when (s) {
                SituationStatus.DRIVING -> "Drive chesthunna, call lift cheyyalenu. Aapaka call chesta."
                SituationStatus.RIDING -> "Bike meeda unna, taruvatha call chesta."
                SituationStatus.INTERVIEW -> "Interview lo unna, taruvatha call chesta."
                SituationStatus.MEETING -> "Meeting lo unna, konchem sepatlo call chesta."
                SituationStatus.GAMING -> "Game adutunna 🎮 konchem sepatlo call chesta."
                SituationStatus.SLEEPING -> "Padukunna 😴 lechaka call chesta."
                SituationStatus.BUSY -> "Konchem busy ga unna, taruvatha call chesta."
                SituationStatus.AVAILABLE -> "Nee call miss ayyindi, konchem sepatlo call chesta."
            }
            LanguageCode.HI -> when (s) {
                SituationStatus.DRIVING -> "Abhi drive kar $hr hoon, rukte hi call $ht hoon."
                SituationStatus.RIDING -> "Bike chala $hr hoon, baad mein call $ht hoon."
                SituationStatus.INTERVIEW -> "Abhi interview mein hoon, baad mein call $ht hoon."
                SituationStatus.MEETING -> "Meeting mein hoon, thodi der mein call $ht hoon."
                SituationStatus.GAMING -> "Game khel $hr hoon 🎮 thodi der mein call $ht hoon."
                SituationStatus.SLEEPING -> "So $hr thi/tha 😴 uthke call $ht hoon.".replace("thi/tha", if (f) "thi" else "tha")
                SituationStatus.BUSY -> "Abhi thoda busy hoon, baad mein call $ht hoon."
                SituationStatus.AVAILABLE -> "Call miss ho gaya, thodi der mein call $ht hoon."
            }
            LanguageCode.TA -> when (s) {
                SituationStatus.DRIVING -> "Ippo drive pannitu iruken, niruthitu call panren."
                SituationStatus.RIDING -> "Bike la poitu iruken, apram call panren."
                SituationStatus.INTERVIEW -> "Interview la iruken, apram call panren."
                SituationStatus.MEETING -> "Meeting la iruken, konja nerathula call panren."
                SituationStatus.GAMING -> "Game aadittu iruken 🎮 konja nerathula call panren."
                SituationStatus.SLEEPING -> "Thoongitu irundhen 😴 ezhundhu call panren."
                SituationStatus.BUSY -> "Konjam busy ah iruken, apram call panren."
                SituationStatus.AVAILABLE -> "Un call miss aayiduchu, konja nerathula call panren."
            }
            LanguageCode.KN -> when (s) {
                SituationStatus.DRIVING -> "Eega drive maadtha idini, nilsidmele call maadtini."
                SituationStatus.RIDING -> "Bike alli idini, aamele call maadtini."
                SituationStatus.INTERVIEW -> "Interview alli idini, aamele call maadtini."
                SituationStatus.MEETING -> "Meeting alli idini, swalpa hottalli call maadtini."
                SituationStatus.GAMING -> "Game aadtha idini 🎮 swalpa hottalli call maadtini."
                SituationStatus.SLEEPING -> "Malgidde 😴 eddmele call maadtini."
                SituationStatus.BUSY -> "Swalpa busy idini, aamele call maadtini."
                SituationStatus.AVAILABLE -> "Nin call miss aaytu, swalpa hottalli call maadtini."
            }
            LanguageCode.EN -> when (s) {
                SituationStatus.DRIVING -> "Driving right now, will call you back once I stop."
                SituationStatus.RIDING -> "On my bike right now, will call you back later."
                SituationStatus.INTERVIEW -> "I'm in an interview right now, will call you back soon."
                SituationStatus.MEETING -> "In a meeting right now, will call you back shortly."
                SituationStatus.GAMING -> "In the middle of a game 🎮 will call you back in a bit."
                SituationStatus.SLEEPING -> "Was sleeping 😴 will call you back when I'm up."
                SituationStatus.BUSY -> "Busy right now, will call you back soon."
                SituationStatus.AVAILABLE -> "Missed your call, will call you back soon."
            }
        }
    }
}
