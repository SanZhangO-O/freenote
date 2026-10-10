package com.zqr.freenote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zqr.freenote.data.SettingsManager
import com.zqr.freenote.data.SharedIntake
import com.zqr.freenote.data.sync.SyncManager
import com.zqr.freenote.notification.NotificationHelper
import com.zqr.freenote.ui.screen.LockScreen
import com.zqr.freenote.ui.screen.NoteEditScreen
import com.zqr.freenote.ui.screen.NoteEditViewModel
import com.zqr.freenote.ui.screen.NoteListScreen
import com.zqr.freenote.ui.screen.SettingsScreen
import com.zqr.freenote.ui.screen.TrashScreen
import com.zqr.freenote.ui.theme.FreenoteTheme
import com.zqr.freenote.viewmodel.NoteViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val openRouteRequest = MutableStateFlow<String?>(null)

    private fun handleOpenRequest(intent: Intent?) {
        when {
            // 系统分享进入（其他应用「分享文本到 FreeNote」）：预填新建便签
            intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                SharedIntake.put(
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT)
                )
                openRouteRequest.value = Routes.EDIT_NEW
            }
            intent?.getBooleanExtra(EXTRA_OPEN_NEW, false) == true ||
                intent?.getStringExtra(EXTRA_OPEN_NEW) == "true" ->
                openRouteRequest.value = Routes.EDIT_NEW
            intent?.getStringExtra(NotificationHelper.EXTRA_NOTE_ID) != null ->
                openRouteRequest.value = Routes.edit(intent.getStringExtra(NotificationHelper.EXTRA_NOTE_ID))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须调用，否则 postSplashScreenTheme 不生效，Activity 会一直停留在启动主题，
        // 其窗口标题栏会与 Compose 的 TopAppBar 叠加成重复标题
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SettingsManager.init(applicationContext)
        SyncManager.init(applicationContext)
        enableEdgeToEdge()
        handleOpenRequest(intent)
        setContent {
            FreenoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FreeNoteApp(openRouteRequest = openRouteRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenRequest(intent)
    }

    override fun onStart() {
        super.onStart()
        // P2P 服务与局域网发现只在 App 前台运行；离开前台自动停掉监听
        SyncManager.start()
    }

    override fun onStop() {
        SyncManager.stop()
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_NEW = "com.zqr.freenote.extra.OPEN_NEW"
    }
}

@Composable
fun FreeNoteApp(openRouteRequest: MutableStateFlow<String?> = MutableStateFlow(null)) {
    val navController = rememberNavController()
    val viewModel: NoteViewModel = viewModel()
    val openRoute by openRouteRequest.collectAsState()

    // 来自通知/小组件的打开请求：导航到对应编辑页（锁定便签由编辑页引导解锁）
    LaunchedEffect(openRoute) {
        openRoute?.let { route ->
            openRouteRequest.value = null
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = Routes.NOTES) {
        composable(Routes.NOTES) {
            NoteListScreen(
                viewModel = viewModel,
                onNavigateToEdit = { noteId ->
                    navController.navigate(Routes.edit(noteId))
                },
                onNavigateToLock = { noteId -> navController.navigate(Routes.lock(noteId)) },
                onNavigateToTrash = { navController.navigate(Routes.TRASH) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(
            route = Routes.EDIT,
            arguments = listOf(Routes.noteIdNavArg())
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString(Routes.ARG_NOTE_ID)
            val editViewModel: NoteEditViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
                key = "note_edit_$noteId",
                factory = NoteEditViewModel.factory(noteId, viewModel)
            )
            NoteEditScreen(
                noteId = noteId,
                editViewModel = editViewModel,
                onNavigateBack = { navController.popBackStack() },
                onUnlockRequested = { noteId?.let { navController.navigate(Routes.lock(it)) } },
                onNavigateToEdit = { id -> navController.navigate(Routes.edit(id)) }
            )
        }
        composable(Routes.EDIT_NEW) { backStackEntry ->
            val editViewModel: NoteEditViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
                key = "note_edit_new",
                factory = NoteEditViewModel.factory(null, viewModel)
            )
            NoteEditScreen(
                noteId = null,
                editViewModel = editViewModel,
                onNavigateBack = { navController.popBackStack() },
                onUnlockRequested = {},
                onNavigateToEdit = { id -> navController.navigate(Routes.edit(id)) }
            )
        }
        composable(Routes.TRASH) {
            TrashScreen(viewModel = viewModel, onNavigateBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = viewModel, onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.LOCK,
            arguments = listOf(Routes.noteIdNavArg())
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString(Routes.ARG_NOTE_ID) ?: return@composable
            LockScreen(
                onUnlocked = { pin ->
                    viewModel.setUnlockPin(pin)
                    navController.navigate(Routes.edit(noteId)) {
                        popUpTo(Routes.NOTES)
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }
    }
}
