package com.iamode.app.core

/** Whether the app is on screen (set by MainActivity). Background work uses it to pick notification vs in-app. */
object AppVisibility {
    @Volatile var foreground: Boolean = false
}
