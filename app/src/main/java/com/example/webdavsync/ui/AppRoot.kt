package com.example.webdavsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.webdavsync.WebDavSyncApp
import com.example.webdavsync.ui.sync.SyncProgressScreen
import com.example.webdavsync.ui.task.TaskEditScreen
import com.example.webdavsync.ui.task.TaskHistoryScreen
import com.example.webdavsync.ui.task.TaskListScreen
import androidx.compose.ui.platform.LocalContext

object Routes {
    const val LIST = "list"
    const val EDIT_ID = "edit/{taskId}"   // taskId=0 表示新建,>0 表示编辑
    const val SYNC = "sync"
    const val HISTORY_ID = "history/{taskId}"
    fun edit(taskId: Long) = "edit/$taskId"
    fun history(taskId: Long) = "history/$taskId"
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val container = remember { (context.applicationContext as WebDavSyncApp).container }

    NavHost(navController = navController, startDestination = Routes.LIST) {

        // 任务列表(主页)
        composable(Routes.LIST) {
            TaskListScreen(
                onNewTask = { navController.navigate(Routes.edit(0L)) },
                onEditTask = { id -> navController.navigate(Routes.edit(id)) },
                onSync = { id ->
                    // 启动前台同步服务,并跳转进度页
                    com.example.webdavsync.service.SyncService.start(context, id)
                    navController.navigate(Routes.SYNC)
                },
                onSyncAll = { ids ->
                    // 同步全部已启用任务
                    com.example.webdavsync.service.SyncService.startAll(context, ids)
                    navController.navigate(Routes.SYNC)
                },
                onOpenHistory = { id -> navController.navigate(Routes.history(id)) }
            )
        }

        // 编辑/新建
        composable(
            route = Routes.EDIT_ID,
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getLong("taskId") ?: 0L
            val existing by container.syncTaskDao.observeById(taskId).collectAsState(initial = null)
            TaskEditScreen(
                taskId = taskId,
                existing = existing,
                onSaved = { navController.popBackStack(Routes.LIST, inclusive = false) },
                onBack = { navController.popBackStack() }
            )
        }

        // 同步进度
        composable(Routes.SYNC) {
            SyncProgressScreen(
                onBack = { navController.popBackStack(Routes.LIST, inclusive = false) },
                onCompleted = { navController.popBackStack(Routes.LIST, inclusive = false) }
            )
        }

        // 同步历史
        composable(
            route = Routes.HISTORY_ID,
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getLong("taskId") ?: 0L
            val task by container.syncTaskDao.observeById(taskId).collectAsState(initial = null)
            TaskHistoryScreen(
                taskId = taskId,
                taskName = task?.name ?: "任务",
                onBack = { navController.popBackStack() }
            )
        }
    }
}
