package com.iamode.app.core.di

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class BackendClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class GmailClient
