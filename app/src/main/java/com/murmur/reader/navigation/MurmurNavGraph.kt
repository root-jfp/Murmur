package com.murmur.reader.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.murmur.reader.R
import com.murmur.reader.ui.screens.library.LibraryScreen
import com.murmur.reader.ui.screens.reader.ReaderScreen
import com.murmur.reader.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Reader : Screen("reader")
    object Library : Screen("library")
    object Settings : Screen("settings")
}

@Composable
fun MurmurNavGraph(
    sharedText: String? = null,
    sharedUri: Any? = null
) {
    val navController = rememberNavController()

    val items = listOf(
        Triple(Screen.Reader, Icons.Filled.Home, R.string.nav_reader),
        Triple(Screen.Library, Icons.Filled.Book, R.string.nav_library),
        Triple(Screen.Settings, Icons.Filled.Settings, R.string.nav_settings),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { (screen, icon, labelRes) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = stringResource(labelRes)) },
                        label = { Text(stringResource(labelRes)) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Reader.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Reader.route) {
                ReaderScreen(
                    initialText = sharedText,
                    initialUri = sharedUri as? Uri
                )
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    onDocumentSelected = { uri ->
                        navController.navigate(Screen.Reader.route)
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
