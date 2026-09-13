package com.sukisu.ultra.ui.screen.appprofile

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.sukisu.ultra.Natives
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Route
import com.sukisu.ultra.ui.util.LocalSnackbarHost
import com.sukisu.ultra.ui.util.forceStopApp
import com.sukisu.ultra.ui.util.getSepolicy
import com.sukisu.ultra.ui.util.launchApp
import com.sukisu.ultra.ui.util.restartApp
import com.sukisu.ultra.ui.util.setSepolicy
import com.sukisu.ultra.ui.viewmodel.SuperUserViewModel
import com.sukisu.ultra.ui.viewmodel.getTemplateInfoById

private data class PendingGrantRequest(
    val name: String,
    val currentUid: Int,
    val rootUseDefault: Boolean,
    val rootTemplate: String?,
    val uid: Int,
    val gid: Int,
    val groups: List<Int>,
    val capabilities: List<Int>,
    val context: String,
    val namespace: Int,
    val nonRootUseDefault: Boolean,
    val umountModules: Boolean,
    val rules: String,
) {
    companion object {
        fun fromProfile(profile: Natives.Profile): PendingGrantRequest {
            return PendingGrantRequest(
                name = profile.name,
                currentUid = profile.currentUid,
                rootUseDefault = profile.rootUseDefault,
                rootTemplate = profile.rootTemplate,
                uid = profile.uid,
                gid = profile.gid,
                groups = profile.groups,
                capabilities = profile.capabilities,
                context = profile.context,
                namespace = profile.namespace,
                nonRootUseDefault = profile.nonRootUseDefault,
                umountModules = profile.umountModules,
                rules = profile.rules
            )
        }
    }
}

@Composable
fun AppProfileScreen(uid: Int) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val packageName = primaryAppInfo.packageName
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName) {
        Natives.getAppProfile(packageName, uid).also {
            if (it.allowSu) {
                it.rules = getSepolicy(packageName)
            }
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)
    val confirmGrantRootAgain = stringResource(R.string.confirm_grant_root_again).format(primaryAppInfo.label)
    var pendingGrantRequest by remember(uid, packageName) {
        mutableStateOf<PendingGrantRequest?>(null)
    }
    var pendingGrantExpiry by remember(uid, packageName) {
        mutableStateOf(0L)
    }

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile,
        appGroup = appGroup,
        sharedUserId = sharedUserId,
    )

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onLaunchApp = ::launchApp,
        onForceStopApp = ::forceStopApp,
        onRestartApp = ::restartApp,
        onViewTemplate = { templateId ->
            getTemplateInfoById(templateId)?.let { info ->
                navigator.push(Route.TemplateEditor(info, true))
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
        onProfileChange = { updatedProfile ->
            scope.launch {
                val now = SystemClock.elapsedRealtime()
                val request = PendingGrantRequest.fromProfile(updatedProfile)
                val pendingExpired = pendingGrantRequest != null && now > pendingGrantExpiry
                val pendingMismatch = pendingGrantRequest != null &&
                        (!updatedProfile.allowSu || pendingGrantRequest != request)
                if (pendingExpired || pendingMismatch) {
                    pendingGrantRequest = null
                    pendingGrantExpiry = 0L
                }
                val pendingConfirmed = updatedProfile.allowSu
                        && pendingGrantRequest == request
                        && now <= pendingGrantExpiry

                if (updatedProfile.allowSu) {
                    if (uid < 2000 && uid != 1000) {
                        pendingGrantRequest = null
                        pendingGrantExpiry = 0L
                        showMessage(suNotAllowed)
                        return@launch
                    }
                    if (!pendingConfirmed && updatedProfile != profile) {
                        pendingGrantRequest = request
                        pendingGrantExpiry = now + 15_000L
                        showMessage(confirmGrantRootAgain)
                        return@launch
                    }
                    pendingGrantRequest = null
                    pendingGrantExpiry = 0L
                } else {
                    pendingGrantRequest = null
                    pendingGrantExpiry = 0L
                }
                if (!Natives.setAppProfile(updatedProfile)) {
                    pendingGrantRequest = null
                    pendingGrantExpiry = 0L
                    showMessage(failToUpdateAppProfile)
                } else {
                    pendingGrantRequest = null
                    pendingGrantExpiry = 0L
                    if (updatedProfile.allowSu
                        && !updatedProfile.rootUseDefault
                        && updatedProfile.rules.isNotEmpty()
                        && !setSepolicy(updatedProfile.name, updatedProfile.rules)
                    ) {
                        val profileRollbackSuccess = Natives.setAppProfile(profile)
                        val sepolicyRollbackSuccess = if (profile.allowSu && !profile.rootUseDefault) {
                            setSepolicy(profile.name, profile.rules)
                        } else {
                            setSepolicy(updatedProfile.name, "")
                        }
                        if (!profileRollbackSuccess || !sepolicyRollbackSuccess) {
                            showMessage(failToUpdateAppProfile)
                            return@launch
                        }
                        showMessage(failToUpdateSepolicy)
                        return@launch
                    }
                    profile = updatedProfile
                    if (uiMode == UiMode.Material) {
                        viewModel.loadAppList()
                    }
                }
            }
        },
    )

    when (uiMode) {
        UiMode.Miuix -> AppProfileScreenMiuix(
            state = state,
            actions = actions,
        )

        UiMode.Material -> AppProfileScreenMaterial(
            state = state,
            actions = actions,
        )
    }
}
