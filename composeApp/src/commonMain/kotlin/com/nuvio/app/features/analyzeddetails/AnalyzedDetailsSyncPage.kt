package com.nuvio.app.features.analyzeddetails

import androidx.compose.foundation.lazy.LazyListScope

internal fun LazyListScope.analyzedDetailsSyncPage(isTablet: Boolean) {
    item {
        AnalyzedDetailsSyncSettings(isTablet)
    }
}
