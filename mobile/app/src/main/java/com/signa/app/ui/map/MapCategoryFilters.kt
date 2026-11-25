package com.signa.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.signa.app.R

@Composable
internal fun MapCategoryFilters(
    state: MapUiState,
    onToggleCategory: (String) -> Unit,
    onClear: () -> Unit,
    onSpeedFilter: (Int?) -> Unit
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        FilterChip(state.activeCategories.isEmpty(), onClear, label = { Text(stringResource(R.string.map_all_signs)) })
        state.categories.forEach { category ->
            FilterChip(category.code in state.activeCategories, { onToggleCategory(category.code) },
                label = { Text(category.name) })
        }
        if (state.sampleSigns) {
            FilterChip(state.speedFilter == null, { onSpeedFilter(null) },
                label = { Text(stringResource(R.string.map_all_speeds)) })
            state.availableSpeedLimits.forEach { speed ->
                FilterChip(state.speedFilter == speed, { onSpeedFilter(speed) },
                    label = { Text(stringResource(R.string.map_speed_filter, speed)) })
            }
        }
    }
}
