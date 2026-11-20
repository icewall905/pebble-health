package org.tdgs.ph.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.screens.ActivitiesScreen
import org.tdgs.ph.ui.screens.ActivityDetailScreen
import org.tdgs.ph.ui.screens.InsightsScreen
import org.tdgs.ph.ui.screens.MetricDetailScreen
import org.tdgs.ph.ui.screens.SettingsScreen
import org.tdgs.ph.ui.screens.SetupScreen
import org.tdgs.ph.ui.screens.TodayScreen
import org.tdgs.ph.ui.screens.TrendsScreen
import org.tdgs.ph.ui.theme.LocalMetrics

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Rounded.WbSunny),
    Tab("activities", "Activities", Icons.AutoMirrored.Rounded.DirectionsRun),
    Tab("trends", "Trends", Icons.Rounded.CalendarMonth),
    Tab("insights", "Insights", Icons.Rounded.AutoAwesome),
    Tab("settings", "Settings", Icons.Rounded.Settings),
)

@Composable
fun PhRoot(repo: Repository) {
    val config by repo.settings.config.collectAsState(initial = Unit)
    when (config) {
        Unit -> return // DataStore not read yet
        null -> SetupScreen(repo)
        else -> Main(repo)
    }
}

@Composable
private fun Main(repo: Repository) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val state by repo.state.collectAsState()
    LaunchedEffect(Unit) {
        val age = state.fetchedAt?.let { System.currentTimeMillis() / 1000 - it } ?: Long.MAX_VALUE
        if (age > 120) repo.refresh()
    }
    Scaffold(
        bottomBar = { if (tabs.any { it.route == route }) BottomBar(nav, route) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        NavHost(nav, startDestination = "today", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("today") { TodayScreen(repo, open = { nav.navigate(it) }) }
            composable("activities") { ActivitiesScreen(repo, open = { nav.navigate("activity/$it") }) }
            composable("trends") { TrendsScreen(repo, open = { nav.navigate(it) }) }
            composable("insights") { InsightsScreen(repo, open = { nav.navigate(it) }) }
            composable("settings") { SettingsScreen(repo) }
            composable("metric/{name}") { e ->
                MetricDetailScreen(repo, e.arguments?.getString("name") ?: "steps", back = { nav.popBackStack() })
            }
            composable("activity/{id}") { e ->
                ActivityDetailScreen(repo, e.arguments?.getString("id") ?: "", back = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    val m = LocalMetrics.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        tabs.forEach { t ->
            NavigationBarItem(
                selected = route == t.route,
                onClick = {
                    nav.navigate(t.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(t.icon, t.label) },
                label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    unselectedIconColor = m.muted, unselectedTextColor = m.muted,
                ),
            )
        }
    }
}

