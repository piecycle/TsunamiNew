package app.aaps.ui.compose.tsunamiDialog

import androidx.compose.runtime.Immutable

@Immutable
data class TsunamiDialogUiState(
    // User input
    val insulin: Double = 0.0,
    val duration: Double = 0.0,
    val notes: String = "",
    val eventTime: Long = System.currentTimeMillis(),

    // Runtime status (refreshed on init / after commit)
    val isTsunamiActive: Boolean = false,

    // Config (set once during init)
    val maxInsulin: Double = 0.0,
    val bolusStep: Double = 0.1,
    val tsunamiButtonIncrement1: Double = 0.5,
    val tsunamiButtonIncrement2: Double = 1.0,
    val tsunamiButtonIncrement3: Double = 2.0,
    val maxDurationMinutes: Double = 300.0,
    val showNotesFromPreferences: Boolean = false,
)

val TsunamiDialogUiState.confirmEnabled: Boolean
    // Mirrors the old validateInputs()/submit() gate: something to confirm exists if there's a bolus,
    // a duration to start, or an active Tsunami to cancel via the OK button (duration == 0 + active).
    get() = insulin > 0.0 || duration > 0.0 || isTsunamiActive