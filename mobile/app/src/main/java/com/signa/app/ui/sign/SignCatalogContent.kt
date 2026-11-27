package com.signa.app.ui.sign

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signa.app.R
import com.signa.app.data.remote.backend.dto.SignTypeDto
import com.signa.app.ui.components.SignaWordmark
import com.signa.app.ui.components.SignaPage
import com.signa.app.ui.components.SpeedLimitBadge

@Composable
internal fun SignCatalogContent(
    onOpenType: (String) -> Unit,
    header: @Composable () -> Unit = {},
    viewModel: SignCatalogViewModel = hiltViewModel()
) {
    val snapshot by viewModel.catalog.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }

    val categories = remember(snapshot) { snapshot?.categories.orEmpty().sortedBy { it.sortOrder } }
    val effectiveGroup = group?.takeIf { code -> categories.any { it.code == code } }
    val matching = remember(snapshot, effectiveGroup, query) {
        val keyword = normalizeSignSearch(query)
        snapshot?.types?.items.orEmpty().filter { type ->
            (effectiveGroup == null || effectiveGroup == type.categoryCode) &&
                (normalizeSignSearch(type.name).contains(keyword) || normalizeSignSearch(type.code).contains(keyword))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "lookup-header") {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                SignaWordmark()
                Text(stringResource(R.string.lookup_title), style = MaterialTheme.typography.headlineMedium)
                header()
            }
        }
        item(key = "catalog-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sign_catalog_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.sign_catalog_search)) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(group == null, { group = null }, label = { Text(stringResource(R.string.map_all_signs)) })
                    categories.forEach { category ->
                        FilterChip(group == category.code, { group = category.code }, label = { Text(category.name) })
                    }
                }
            }
        }
        if (loading && snapshot == null) item(key = "loading") { CircularProgressIndicator() }
        if (failed) item(key = "error") {
            Column {
                Text(stringResource(if (snapshot != null) R.string.sign_catalog_cached else R.string.data_load_error))
                TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
            }
        }
        if (!loading && matching.isEmpty()) item(key = "empty") { Text(stringResource(R.string.no_signs_found)) }
        items(matching, key = { "type:${it.code}" }, contentType = { "sign-type" }) { type ->
            Surface(onClick = { onOpenType(CATALOG_TYPE_PREFIX + type.code) }, modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SpeedLimitBadge(type.speedKmh, Modifier.size(40.dp))
                    Text("${type.code} · ${type.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun SignCatalogTypeDetail(
    code: String,
    onBack: () -> Unit,
    onShowOnMap: (String) -> Unit,
    viewModel: SignCatalogViewModel = hiltViewModel()
) {
    val snapshot by viewModel.catalog.collectAsStateWithLifecycle()
    val fetched by viewModel.type.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val type = fetched ?: snapshot?.types?.items?.firstOrNull { it.code == code }
    SignaPage(stringResource(R.string.sign_catalog_title), onBack = onBack) {
        when {
            type != null -> {
                SignTypeDescription(type, snapshot?.categories?.firstOrNull { it.code == type.categoryCode }?.name)
                TextButton(onClick = { onShowOnMap(code) }) { Text(stringResource(R.string.sign_catalog_open_map)) }
            }
            loading -> CircularProgressIndicator()
            failed -> {
                Text(stringResource(R.string.data_load_error))
                TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
            }
            else -> Text(stringResource(R.string.sign_not_found))
        }
    }
}

@Composable
internal fun SignTypeDescription(type: SignTypeDto, category: String?) {
    if (type.iconUrl != null) {
        SignRemoteImage(type.iconUrl, stringResource(R.string.sign_catalog_illustration), Modifier.size(112.dp)) {
            SpeedLimitBadge(type.speedKmh, Modifier.size(88.dp))
        }
    } else {
        SpeedLimitBadge(type.speedKmh, Modifier.size(88.dp))
    }
    Text("${type.code} · ${type.name}", style = MaterialTheme.typography.titleLarge)
    Text(category ?: type.categoryCode, style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.sign_meaning_title), style = MaterialTheme.typography.titleMedium)
    Text(type.description ?: stringResource(R.string.sign_catalog_no_description))
}
