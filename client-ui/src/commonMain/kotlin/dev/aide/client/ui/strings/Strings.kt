package dev.aide.client.ui.strings

import androidx.compose.runtime.Composable
import dev.aide.client.ui.resources.Res
import dev.aide.client.ui.resources.action_back
import dev.aide.client.ui.resources.action_settings
import dev.aide.client.ui.resources.agent_post_task
import dev.aide.client.ui.resources.agent_request_failed
import dev.aide.client.ui.resources.agent_task_hint
import dev.aide.client.ui.resources.agent_title
import dev.aide.client.ui.resources.app_name
import dev.aide.client.ui.resources.connection_closed
import dev.aide.client.ui.resources.connection_connecting
import dev.aide.client.ui.resources.connection_incompatible_client_outdated
import dev.aide.client.ui.resources.connection_incompatible_host_outdated
import dev.aide.client.ui.resources.connection_incompatible_malformed
import dev.aide.client.ui.resources.connection_reconnecting
import dev.aide.client.ui.resources.repo_file_title
import dev.aide.client.ui.resources.repo_file_truncated
import dev.aide.client.ui.resources.repo_header_branch
import dev.aide.client.ui.resources.repo_header_root
import dev.aide.client.ui.resources.repo_tree_title
import dev.aide.client.ui.resources.repo_tree_truncated
import dev.aide.client.ui.resources.run_state_failed
import dev.aide.client.ui.resources.run_state_finished
import dev.aide.client.ui.resources.run_state_interrupted
import dev.aide.client.ui.resources.run_state_none
import dev.aide.client.ui.resources.run_state_paused
import dev.aide.client.ui.resources.run_state_planned
import dev.aide.client.ui.resources.run_state_running
import dev.aide.client.ui.resources.run_state_stopped
import dev.aide.client.ui.resources.task_failure_generic
import dev.aide.client.ui.resources.task_failure_host_restart
import dev.aide.client.ui.resources.task_failure_not_configured
import dev.aide.client.ui.resources.task_failure_plan_unreadable
import dev.aide.client.ui.resources.task_failure_user_stop
import dev.aide.client.ui.resources.task_status_accepted
import dev.aide.client.ui.resources.task_status_failed
import dev.aide.client.ui.resources.task_status_none
import dev.aide.client.ui.resources.task_status_queued
import dev.aide.client.ui.resources.task_status_rejected
import dev.aide.client.ui.resources.task_status_review
import dev.aide.client.ui.resources.task_status_running
import dev.aide.client.ui.resources.settings_control_mode
import dev.aide.client.ui.resources.settings_control_mode_buttons
import dev.aide.client.ui.resources.settings_control_mode_gestures
import dev.aide.client.ui.resources.settings_control_mode_hybrid
import dev.aide.client.ui.resources.settings_host_endpoint
import dev.aide.client.ui.resources.settings_host_endpoint_apply
import dev.aide.client.ui.resources.settings_host_endpoint_hint
import dev.aide.client.ui.resources.settings_host_endpoint_restart
import dev.aide.client.ui.resources.settings_repository_apply
import dev.aide.client.ui.resources.settings_repository_path
import dev.aide.client.ui.resources.settings_repository_path_hint
import dev.aide.client.ui.resources.settings_theme
import dev.aide.client.ui.resources.settings_theme_dark
import dev.aide.client.ui.resources.settings_theme_light
import dev.aide.client.ui.resources.settings_theme_system
import dev.aide.client.ui.resources.settings_title
import dev.aide.client.ui.resources.state_empty_repo
import dev.aide.client.ui.resources.state_empty_title
import dev.aide.client.ui.resources.state_empty_tree
import dev.aide.client.ui.resources.state_error_not_a_repo
import dev.aide.client.ui.resources.state_error_path_missing
import dev.aide.client.ui.resources.state_error_retry
import dev.aide.client.ui.resources.state_error_title
import dev.aide.client.ui.resources.state_error_workspace_closed
import dev.aide.client.ui.resources.state_loading_skeleton
import dev.aide.client.ui.resources.state_loading_title
import dev.aide.client.ui.resources.state_no_permission_body
import dev.aide.client.ui.resources.state_no_permission_title
import dev.aide.client.ui.resources.state_no_repository
import dev.aide.client.ui.resources.state_offline_body
import dev.aide.client.ui.resources.state_offline_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Единая точка доступа к строкам интерфейса (NFR-13).
 *
 * Экраны не держат литералов: тест [dev.aide.client.ui.NoLiteralUiStringsTest]
 * падает, если в общих экранах появится строковый литерал внутри composable.
 */
object Strings {

    val appName: StringResource = Res.string.app_name
    val repoHeaderBranch: StringResource = Res.string.repo_header_branch
    val repoHeaderRoot: StringResource = Res.string.repo_header_root
    val repoTreeTitle: StringResource = Res.string.repo_tree_title
    val repoFileTitle: StringResource = Res.string.repo_file_title
    val repoFileTruncated: StringResource = Res.string.repo_file_truncated
    val repoTreeTruncated: StringResource = Res.string.repo_tree_truncated

    val actionBack: StringResource = Res.string.action_back
    val actionSettings: StringResource = Res.string.action_settings

    val agentTitle: StringResource = Res.string.agent_title
    val agentTaskHint: StringResource = Res.string.agent_task_hint
    val agentPostTask: StringResource = Res.string.agent_post_task
    val runStateNone: StringResource = Res.string.run_state_none
    val runStatePlanned: StringResource = Res.string.run_state_planned
    val runStateRunning: StringResource = Res.string.run_state_running
    val runStatePaused: StringResource = Res.string.run_state_paused
    val runStateFinished: StringResource = Res.string.run_state_finished
    val runStateFailed: StringResource = Res.string.run_state_failed
    val runStateStopped: StringResource = Res.string.run_state_stopped
    val runStateInterrupted: StringResource = Res.string.run_state_interrupted

    val taskStatusNone: StringResource = Res.string.task_status_none
    val taskStatusQueued: StringResource = Res.string.task_status_queued
    val taskStatusRunning: StringResource = Res.string.task_status_running
    val taskStatusReview: StringResource = Res.string.task_status_review
    val taskStatusAccepted: StringResource = Res.string.task_status_accepted
    val taskStatusRejected: StringResource = Res.string.task_status_rejected
    val taskStatusFailed: StringResource = Res.string.task_status_failed
    val taskFailureNotConfigured: StringResource = Res.string.task_failure_not_configured
    val taskFailurePlanUnreadable: StringResource = Res.string.task_failure_plan_unreadable
    val taskFailureUserStop: StringResource = Res.string.task_failure_user_stop
    val taskFailureHostRestart: StringResource = Res.string.task_failure_host_restart
    val taskFailureGeneric: StringResource = Res.string.task_failure_generic
    val agentRequestFailed: StringResource = Res.string.agent_request_failed

    val stateLoadingTitle: StringResource = Res.string.state_loading_title
    val stateLoadingSkeleton: StringResource = Res.string.state_loading_skeleton
    val stateEmptyTitle: StringResource = Res.string.state_empty_title
    val stateEmptyRepo: StringResource = Res.string.state_empty_repo
    val stateEmptyTree: StringResource = Res.string.state_empty_tree
    val stateNoRepository: StringResource = Res.string.state_no_repository
    val stateErrorTitle: StringResource = Res.string.state_error_title
    val stateErrorRetry: StringResource = Res.string.state_error_retry
    val stateErrorPathMissing: StringResource = Res.string.state_error_path_missing
    val stateErrorNotARepo: StringResource = Res.string.state_error_not_a_repo
    val stateErrorWorkspaceClosed: StringResource = Res.string.state_error_workspace_closed
    val stateOfflineTitle: StringResource = Res.string.state_offline_title
    val stateOfflineBody: StringResource = Res.string.state_offline_body
    val stateNoPermissionTitle: StringResource = Res.string.state_no_permission_title
    val stateNoPermissionBody: StringResource = Res.string.state_no_permission_body

    val settingsTitle: StringResource = Res.string.settings_title
    val settingsRepositoryPath: StringResource = Res.string.settings_repository_path
    val settingsRepositoryPathHint: StringResource = Res.string.settings_repository_path_hint
    val settingsRepositoryApply: StringResource = Res.string.settings_repository_apply
    val settingsHostEndpoint: StringResource = Res.string.settings_host_endpoint
    val settingsHostEndpointHint: StringResource = Res.string.settings_host_endpoint_hint
    val settingsHostEndpointApply: StringResource = Res.string.settings_host_endpoint_apply
    val settingsHostEndpointRestart: StringResource = Res.string.settings_host_endpoint_restart
    val settingsTheme: StringResource = Res.string.settings_theme
    val settingsThemeSystem: StringResource = Res.string.settings_theme_system
    val settingsThemeLight: StringResource = Res.string.settings_theme_light
    val settingsThemeDark: StringResource = Res.string.settings_theme_dark
    val settingsControlMode: StringResource = Res.string.settings_control_mode
    val settingsControlModeGestures: StringResource = Res.string.settings_control_mode_gestures
    val settingsControlModeButtons: StringResource = Res.string.settings_control_mode_buttons
    val settingsControlModeHybrid: StringResource = Res.string.settings_control_mode_hybrid

    val connectionConnecting: StringResource = Res.string.connection_connecting
    val connectionReconnecting: StringResource = Res.string.connection_reconnecting
    val connectionIncompatibleClientOutdated: StringResource = Res.string.connection_incompatible_client_outdated
    val connectionIncompatibleHostOutdated: StringResource = Res.string.connection_incompatible_host_outdated
    val connectionIncompatibleMalformed: StringResource = Res.string.connection_incompatible_malformed
    val connectionClosed: StringResource = Res.string.connection_closed

    /** Читает строку в composable-контексте. */
    @Composable
    fun text(resource: StringResource): String = stringResource(resource)

    /** Читает строку с подстановками. */
    @Composable
    fun text(resource: StringResource, vararg args: Any): String = stringResource(resource, *args)
}
