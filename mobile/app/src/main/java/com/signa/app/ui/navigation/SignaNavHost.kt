package com.signa.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.signa.app.ui.account.AccountRoute
import com.signa.app.ui.auth.AuthMode
import com.signa.app.ui.auth.AuthRoute
import com.signa.app.ui.preview.ProfilePreviewRoute
import com.signa.app.ui.contributions.ContributionsRoute
import com.signa.app.ui.drive.ActiveDriveRoute
import com.signa.app.ui.drive.DriveScreen
import com.signa.app.ui.map.MapRoute
import com.signa.app.ui.settings.SettingsRoute
import com.signa.app.ui.sign.SignDetailRoute
import com.signa.app.ui.sign.SignLookupRoute
import com.signa.app.BuildConfig
import com.signa.app.R
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.signa.app.ui.sign.CATALOG_MAP_ARGUMENT
import com.signa.app.ui.sign.MySignReportsRoute

private const val MY_SIGN_REPORTS = "my-sign-reports"
private const val CATALOG_MAP = "catalog-map/{$CATALOG_MAP_ARGUMENT}"

/** Connects screens and navigation callbacks. Business operations belong in feature state owners. */
@Composable
internal fun SignaNavHost(
    controller: NavHostController,
    onPreviewAction: () -> Unit,
    onMessage: (Int) -> Unit,
    onSignInRequired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val openSign: (String) -> Unit = { id -> controller.navigate(AppRoutes.signDetail(id)) }
    val goBack: () -> Unit = { controller.popBackStack() }


    NavHost(controller, startDestination = AppRoutes.MAP, modifier = modifier) {
        composable(AppRoutes.MAP) {
            MapRoute(onOpenSign = openSign)
        }
        composable(AppRoutes.DRIVE) {
            DriveScreen(onStart = { controller.navigate(AppRoutes.DRIVE_ACTIVE) { launchSingleTop = true } })
        }
        composable(AppRoutes.DRIVE_ACTIVE) {
            ActiveDriveRoute(onEnd = goBack)
        }
        composable(AppRoutes.SIGN_LOOKUP) {
            val onPreviewAction = onSignInRequired // Callback của luồng ảnh khi khách bấm gửi.
            SignLookupRoute(onAction = onPreviewAction, onOpenSign = openSign)
        }
        composable(AppRoutes.SETTINGS) {
            SettingsRoute()
        }
        composable(AppRoutes.ACCOUNT) {
            AccountRoute(
                onLogin = onSignInRequired,
                onProfilePreview = { controller.navigate(AppRoutes.PROFILE_PREVIEW) },
                onContributions = { controller.navigate(AppRoutes.CONTRIBUTIONS) },
                onChangePassword = { controller.navigate(AppRoutes.CHANGE_PASSWORD) }
            )
        }
        composable(AppRoutes.PROFILE_PREVIEW) {
            ProfilePreviewRoute(
                onClose = goBack,
                onContributions = { controller.navigate(AppRoutes.CONTRIBUTIONS) },
                onChangePassword = onPreviewAction
            )
        }
        composable(AppRoutes.CONTRIBUTIONS) {
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = { controller.navigate(MY_SIGN_REPORTS) }, Modifier.padding(start = 20.dp)) {
                    Text(stringResource(R.string.my_sign_reports))
                }
            ContributionsRoute(onBack = goBack, onOpenSign = openSign)
            }
        }
        composable(AppRoutes.CHANGE_PASSWORD) {
            AuthRoute(mode = AuthMode.ChangePassword, onBack = goBack, onPasswordChanged = {
                if (!BuildConfig.USE_FAKE_BACKEND) {
                    onMessage(R.string.auth_change_success_keep_session)
                    goBack()
                    return@AuthRoute
                }
                onMessage(R.string.auth_change_success)
                onSignInRequired()
            })
        }
        composable(
            route = AppRoutes.SIGN_DETAIL,
            arguments = listOf(navArgument(AppRoutes.SIGN_ARGUMENT) { type = NavType.StringType })
        ) {
            SignDetailRoute(
                onBack = goBack,
                signId = requireNotNull(it.arguments?.getString(AppRoutes.SIGN_ARGUMENT)),
                onSignInRequired = onSignInRequired,
                onShowOnMap = { code -> controller.navigate("catalog-map/${Uri.encode(code)}") },
                onReport = onPreviewAction
            )
        }
        composable(CATALOG_MAP, arguments = listOf(navArgument(CATALOG_MAP_ARGUMENT) { type = NavType.StringType })) {
            MapRoute(onOpenSign = openSign, onBackFromType = goBack)
        }
        composable(MY_SIGN_REPORTS) {
            MySignReportsRoute(onBack = goBack, onSignInRequired = onSignInRequired, onOpenSign = openSign)
        }
    }
}
