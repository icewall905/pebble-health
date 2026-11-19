package org.tdgs.ph.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.theme.LocalMetrics

/** Scrollable page with a large title, pull-to-refresh and consistent gutters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(repo: Repository, title: String, subtitle: String? = null, back: (() -> Unit)? = null,
         content: LazyListScope.() -> Unit) {
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { scope.launch { repo.refresh() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    if (back != null) {
                        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    }
                    Column(Modifier.weight(1f).padding(start = if (back == null) 4.dp else 0.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalMetrics.current.muted) }
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                }
            }
            content()
        }
    }
}

@Composable
fun Loading(height: Int = 180) {
    Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalMetrics.current.muted)
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))
