package com.iamode.app.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferenceKeys {
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val MY_NAME = stringPreferencesKey("my_name")
    val GENDER = stringPreferencesKey("gender")
    /** Stored as "CLIENT=AUTO,FRIEND=APPROVE,..." */
    val REPLY_MODES = stringPreferencesKey("reply_modes")
    val EMAIL_AUTO = booleanPreferencesKey("email_auto")
    val MONEY_MODE = stringPreferencesKey("money_mode")
    val MAX_AUTO_REPLIES = intPreferencesKey("max_auto_replies")
    val UNDO_SECONDS = intPreferencesKey("undo_seconds")
    val APPROVAL_NOTIFICATIONS = booleanPreferencesKey("approval_notifications")
    val MISSED_CALL_REPLIES = booleanPreferencesKey("missed_call_replies")
    val REPLY_UNKNOWN_CALLERS = booleanPreferencesKey("reply_unknown_callers")
    val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
    val DEFAULT_SCRIPT = stringPreferencesKey("default_script")
    val VEHICLE_TYPE = stringPreferencesKey("vehicle_type")
    val MANUAL_SITUATION = stringPreferencesKey("manual_situation")
    val DEFAULT_SAVED_RELATIONSHIP = stringPreferencesKey("default_saved_relationship")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val STATUS_NOTIFICATION = booleanPreferencesKey("status_notification")
    val ENABLED_APPS = stringPreferencesKey("enabled_apps")
    val GROUP_REPLIES = booleanPreferencesKey("group_replies")
    val GROUP_NAMES = stringPreferencesKey("group_names")
    val AUTO_DRIVING = booleanPreferencesKey("auto_driving")
    val AUTO_MEETINGS = booleanPreferencesKey("auto_meetings")
    /** Schedules as AutoSchedule.encode() values joined by ';' */
    val AUTO_SCHEDULES = stringPreferencesKey("auto_schedules")
    val CRASH_REPORTS = booleanPreferencesKey("crash_reports")
    val MAIL_INTELLIGENCE = booleanPreferencesKey("mail_intelligence")
    val CELEBRATIONS = booleanPreferencesKey("celebrations")
    val CELEBRATION_SOUND = booleanPreferencesKey("celebration_sound")
    val GMAIL_MIRROR = booleanPreferencesKey("gmail_mirror")
    val GMAIL_LABELS = booleanPreferencesKey("gmail_labels")
    val MATCH_WRITING_STYLE = booleanPreferencesKey("match_writing_style")
    val MAIL_REPLY_LANGUAGE = stringPreferencesKey("mail_reply_language")
    val JOB_REMINDERS = booleanPreferencesKey("job_reminders")
    /** An automatic trigger the user overrode (see AutoModePolicy). */
    val AUTO_SUPPRESSED = stringPreferencesKey("auto_suppressed")

    // Written by the activity-recognition receiver
    val MOTION = stringPreferencesKey("motion") // STILL, VEHICLE, BICYCLE
    val MOTION_AT = longPreferencesKey("motion_at")
}
