package com.iamode.app.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.iamode.app.domain.repository.SettingsRepository
import com.iamode.app.ui.components.LocalNavAnimatedVisibilityScope
import com.iamode.app.ui.components.LocalSharedTransitionScope
import com.iamode.app.ui.feature.alerts.AlertsScreen
import com.iamode.app.ui.feature.contacts.ContactsScreen
import com.iamode.app.ui.feature.diagnostics.DiagnosticsScreen
import com.iamode.app.ui.feature.conversation.ConversationScreen
import com.iamode.app.ui.feature.home.HomeScreen
import com.iamode.app.ui.feature.onboarding.OnboardingScreen
import com.iamode.app.ui.feature.onboarding.PermissionsScreen
import com.iamode.app.ui.feature.settings.SettingsScreen
import com.iamode.app.ui.feature.summary.SummaryScreen
import com.iamode.app.ui.feature.mail.MailActionsScreen
import com.iamode.app.ui.feature.jobs.ApplicationDetailScreen
import com.iamode.app.ui.feature.jobs.ApplicationsScreen
import com.iamode.app.ui.feature.mail.InterviewCalendarScreen
import com.iamode.app.ui.feature.mail.MailDetailScreen
import com.iamode.app.ui.feature.mail.MailHomeScreen
import com.iamode.app.ui.feature.mail.OpportunitiesScreen
import com.iamode.app.ui.feature.mail.ReplyReviewScreen
import com.iamode.app.ui.theme.Motion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val ONBOARDING = "onboarding"
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
    const val CONVERSATION = "conversation/{id}?name={name}&rel={rel}"
    const val ALERTS = "alerts"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val SUMMARY = "summary/{sessionId}"
    const val MAIL_ACTIONS = "mail-actions"
    const val MAIL_INTELLIGENCE = "mail-intelligence"
    const val MAIL_DETAIL = "mail/{emailId}"
    const val MAIL_REPLY = "mail-reply/{actionId}"
    const val MAIL_CALENDAR = "mail-calendar/{actionId}"
    const val OPPORTUNITIES = "opportunities"
    const val APPLICATIONS = "applications?shared={shared}"
    const val APPLICATION = "application/{id}"
    fun applications(shared: String? = null) = if (shared == null) "applications" else "applications?shared=${Uri.encode(shared)}"
    fun application(id: String) = "application/$id"
    fun mailDetail(emailId: String) = "mail/${Uri.encode(emailId)}"
    fun mailReply(actionId: String) = "mail-reply/$actionId"
    fun mailCalendar(actionId: String) = "mail-calendar/$actionId"
    fun summary(sessionId: String) = "summary/$sessionId"

    /** Name and relationship travel with the route so the header can animate in on the very first frame. */
    fun conversation(id: String, name: String = "", relationship: String = "") =
        "conversation/$id?name=${Uri.encode(name)}&rel=${Uri.encode(relationship)}"
}

/** Where a notification or widget wants to take the user. */
sealed interface DeepLink {
    data class Conversation(val id: String) : DeepLink
    data class Summary(val sessionId: String) : DeepLink
    data class Mail(val emailId: String) : DeepLink
    data class Application(val id: String) : DeepLink
    /** Text shared from a job app (LinkedIn, Naukri…) via the Android share sheet. */
    data class SharedJob(val text: String) : DeepLink

    companion object {
        fun from(intent: android.content.Intent?): DeepLink? {
            intent ?: return null
            intent.getStringExtra(com.iamode.app.MainActivity.EXTRA_SESSION_ID)?.let { return Summary(it) }
            intent.getStringExtra(com.iamode.app.MainActivity.EXTRA_EMAIL_ID)?.let { return Mail(it) }
            intent.getStringExtra(com.iamode.app.MainActivity.EXTRA_APPLICATION_ID)?.let { return Application(it) }
            if (intent.action == android.content.Intent.ACTION_SEND && intent.type == "text/plain") {
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return SharedJob(it.take(2000)) }
            }
            intent.getStringExtra(com.iamode.app.MainActivity.EXTRA_CONVERSATION_ID)?.let { return Conversation(it) }
            return null
        }
    }
}

data class RootState(val onboardingDone: Boolean, val dynamicColor: Boolean)

@HiltViewModel
class RootViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    /** null until settings are loaded; the splash screen stays up until then. */
    val state: StateFlow<RootState?> = settings.settings
        .map { RootState(it.onboardingDone, it.useDynamicColor) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

// Shared-axis motion: forward slides in from the right, back reverses it. Short offsets + fades keep it calm.
private fun forwardEnter(): EnterTransition =
    slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { it / 6 } +
        fadeIn(tween(Motion.MEDIUM, delayMillis = 40))

private fun forwardExit(): ExitTransition =
    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccelerate)) { -it / 10 } +
        fadeOut(tween(Motion.SHORT))

private fun backEnter(): EnterTransition =
    slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)) { -it / 10 } +
        fadeIn(tween(Motion.MEDIUM))

private fun backExit(): ExitTransition =
    slideOutHorizontally(tween(Motion.MEDIUM, easing = Motion.EmphasizedAccelerate)) { it / 6 } +
        fadeOut(tween(Motion.SHORT))

private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    enter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? = null,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route, arguments, enterTransition = enter) { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) { content(entry) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(onboardingDone: Boolean, deepLink: DeepLink?, onDeepLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    // Decided once: changing a NavHost's start destination later would reset the back stack.
    val start = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }

    LaunchedEffect(deepLink, onboardingDone) {
        if (onboardingDone && deepLink != null) {
            val route = when (deepLink) {
                is DeepLink.Conversation -> Routes.conversation(deepLink.id)
                is DeepLink.Summary -> Routes.summary(deepLink.sessionId)
                is DeepLink.Mail -> Routes.mailDetail(deepLink.emailId)
                is DeepLink.Application -> Routes.application(deepLink.id)
                is DeepLink.SharedJob -> Routes.applications(shared = deepLink.text)
            }
            nav.navigate(route) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = start,
                enterTransition = { forwardEnter() },
                exitTransition = { forwardExit() },
                popEnterTransition = { backEnter() },
                popExitTransition = { backExit() },
            ) {
                screen(Routes.ONBOARDING) {
                    OnboardingScreen(onFinished = {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    })
                }
                screen(Routes.PERMISSIONS) { PermissionsScreen(onBack = { nav.popBackStack() }) }
                screen(
                    Routes.HOME,
                    enter = {
                        if (initialState.destination.route == Routes.ONBOARDING) {
                            fadeIn(tween(Motion.LONG)) + scaleIn(tween(Motion.LONG, easing = Motion.Emphasized), initialScale = 0.96f)
                        } else forwardEnter()
                    },
                ) {
                    HomeScreen(
                        openConversation = { c -> nav.navigate(Routes.conversation(c.id, c.displayName, c.relationship.name)) },
                        openAlerts = { nav.navigate(Routes.ALERTS) },
                        openContacts = { nav.navigate(Routes.CONTACTS) },
                        openSettings = { nav.navigate(Routes.SETTINGS) },
                        openPermissions = { nav.navigate(Routes.PERMISSIONS) },
                        openSummary = { nav.navigate(Routes.summary(it)) },
                        openDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                        openMail = { nav.navigate(Routes.MAIL_INTELLIGENCE) },
                    )
                }
                screen(
                    Routes.CONVERSATION,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        navArgument("rel") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    ConversationScreen(onBack = { nav.popBackStack() }, openDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) })
                }
                screen(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.ALERTS) {
                    AlertsScreen(onBack = { nav.popBackStack() }, openConversation = { nav.navigate(Routes.conversation(it)) })
                }
                screen(Routes.CONTACTS) { ContactsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SUMMARY, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) {
                    SummaryScreen(
                        onBack = { nav.popBackStack() },
                        openConversation = { c -> nav.navigate(Routes.conversation(c.id, c.displayName, c.relationship.name)) },
                    )
                }
                screen(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        openPermissions = { nav.navigate(Routes.PERMISSIONS) },
                        openDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                        openMailActions = { nav.navigate(Routes.MAIL_ACTIONS) },
                        openMailIntelligence = { nav.navigate(Routes.MAIL_INTELLIGENCE) },
                    )
                }
                screen(Routes.MAIL_ACTIONS) {
                    MailActionsScreen(
                        onBack = { nav.popBackStack() },
                        openReply = { nav.navigate(Routes.mailReply(it)) },
                        openCalendar = { nav.navigate(Routes.mailCalendar(it)) },
                        openEmail = { nav.navigate(Routes.mailDetail(it)) },
                    )
                }
                screen(Routes.MAIL_INTELLIGENCE) {
                    MailHomeScreen(
                        onBack = { nav.popBackStack() },
                        openEmail = { nav.navigate(Routes.mailDetail(it)) },
                        openActions = { nav.navigate(Routes.MAIL_ACTIONS) },
                        openOpportunities = { nav.navigate(Routes.OPPORTUNITIES) },
                        openDocument = { nav.navigate(Routes.mailReply(it)) },
                        openCalendar = { nav.navigate(Routes.mailCalendar(it)) },
                        openApplications = { nav.navigate(Routes.applications()) },
                    )
                }
                screen(Routes.MAIL_DETAIL, arguments = listOf(navArgument("emailId") { type = NavType.StringType })) {
                    MailDetailScreen(
                        onBack = { nav.popBackStack() },
                        openReply = { nav.navigate(Routes.mailReply(it)) },
                        openCalendar = { nav.navigate(Routes.mailCalendar(it)) },
                    )
                }
                screen(Routes.MAIL_REPLY, arguments = listOf(navArgument("actionId") { type = NavType.StringType })) {
                    ReplyReviewScreen(onBack = { nav.popBackStack() })
                }
                screen(Routes.MAIL_CALENDAR, arguments = listOf(navArgument("actionId") { type = NavType.StringType })) {
                    InterviewCalendarScreen(onBack = { nav.popBackStack() })
                }
                screen(Routes.OPPORTUNITIES) {
                    OpportunitiesScreen(onBack = { nav.popBackStack() }, openEmail = { nav.navigate(Routes.mailDetail(it)) },
                        openApplications = { nav.navigate(Routes.applications()) })
                }
                screen(Routes.APPLICATIONS, arguments = listOf(navArgument("shared") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                })) {
                    ApplicationsScreen(onBack = { nav.popBackStack() }, openApplication = { nav.navigate(Routes.application(it)) })
                }
                screen(Routes.APPLICATION, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ApplicationDetailScreen(
                        onBack = { nav.popBackStack() },
                        openEmail = { nav.navigate(Routes.mailDetail(it)) },
                        openReply = { nav.navigate(Routes.mailReply(it)) },
                    )
                }
            }
        }
    }
}
