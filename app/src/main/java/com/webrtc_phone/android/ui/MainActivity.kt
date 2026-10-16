package com.webrtc_phone.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.webrtc_phone.android.AppContainer
import com.webrtc_phone.android.container
import com.webrtc_phone.android.data.model.AuthSession
import com.webrtc_phone.android.ui.home.HomeScreen
import com.webrtc_phone.android.ui.home.HomeViewModel
import com.webrtc_phone.android.ui.home.Routes
import com.webrtc_phone.android.ui.login.LoginScreen
import com.webrtc_phone.android.ui.login.LoginViewModel
import com.webrtc_phone.android.ui.manage.QuickTextsScreen
import com.webrtc_phone.android.ui.manage.SharesScreen
import com.webrtc_phone.android.ui.manage.SharesViewModel
import com.webrtc_phone.android.ui.manage.TagsScreen
import com.webrtc_phone.android.ui.manage.UsersScreen
import com.webrtc_phone.android.ui.manage.UsersViewModel
import com.webrtc_phone.android.ui.settings.AboutScreen
import com.webrtc_phone.android.ui.settings.AppSettingsScreen
import com.webrtc_phone.android.ui.settings.CertificatesScreen
import com.webrtc_phone.android.ui.settings.StreamSettingsScreen
import com.webrtc_phone.android.ui.stream.StreamLauncher
import com.webrtc_phone.android.ui.theme.WebrtcPhoneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val c = container
        setContent {
            val app by c.settings.app.collectAsStateWithLifecycle()
            WebrtcPhoneTheme(app.themeMode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Root(c)
                }
            }
        }
    }
}

@Composable
private fun Root(c: AppContainer) {
    val session by c.sessions.session.collectAsStateWithLifecycle()
    val s = session
    if (s == null) {
        val vm: LoginViewModel = viewModel(key = "login") { LoginViewModel(c) }
        val guest by vm.guestTarget.collectAsStateWithLifecycle()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(guest) {
            guest?.let { StreamLauncher.launch(ctx, it); vm.consumeGuestTarget() }
        }
        LoginScreen(vm)
    } else {
        LoggedIn(c, s)
    }
}

@Composable
private fun LoggedIn(c: AppContainer, s: AuthSession) {
    // key 绑定会话：切换账号时 ViewModel 与仓库全部重建
    val vm: HomeViewModel = viewModel(key = "home:${s.serverUrl}:${s.username}:${s.token.hashCode()}") { HomeViewModel(c, s) }
    val nav = rememberNavController()
    val me by vm.me.collectAsStateWithLifecycle()
    val api = c.sessions.api() ?: return

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable(Routes.HOME) { HomeScreen(vm) { route -> nav.navigate(route) } }
        composable(
            Routes.SHARES + "?deviceId={deviceId}&create={create}",
            arguments = listOf(
                navArgument("deviceId") { type = NavType.StringType; defaultValue = "" },
                navArgument("create") { type = NavType.BoolType; defaultValue = false },
            )
        ) { entry ->
            val deviceId = entry.arguments?.getString("deviceId").orEmpty().ifBlank { null }
            val create = entry.arguments?.getBoolean("create") ?: false
            val svm: SharesViewModel = viewModel { SharesViewModel(api, deviceId) }
            val devices by vm.devices.collectAsStateWithLifecycle()
            SharesScreen(svm, devices, create) { nav.popBackStack() }
        }
        composable(Routes.USERS) {
            val uvm: UsersViewModel = viewModel { UsersViewModel(api) }
            UsersScreen(uvm, s.username) { nav.popBackStack() }
        }
        composable(Routes.TAGS) { TagsScreen(vm.repo) { nav.popBackStack() } }
        composable(Routes.QUICK_TEXTS) { QuickTextsScreen(vm.quickTexts) { nav.popBackStack() } }
        composable(Routes.STREAM_SETTINGS) { StreamSettingsScreen(c.settings, me?.policy) { nav.popBackStack() } }
        composable(Routes.APP_SETTINGS) { AppSettingsScreen(c.settings) { nav.popBackStack() } }
        composable(Routes.CERTS) { CertificatesScreen(c.pinStore) { nav.popBackStack() } }
        composable(Routes.ABOUT) { AboutScreen { nav.popBackStack() } }
    }
}
