package app.aaps.ui.compose.tsunamiDialog

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.clientcontrol.ActionProgress
import app.aaps.core.ui.clientcontrol.failTextResId
import app.aaps.core.interfaces.clientcontrol.FailureReason
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
@Stable
class TsunamiDialogViewModel @Inject constructor(
    private val constraintChecker: ConstraintsChecker,
    activePlugin: ActivePlugin,
    val config: Config,
    private val automation: Automation,
    val decimalFormatter: DecimalFormatter,
    val preferences: Preferences,
    val rh: ResourceHelper,
    val dateUtil: DateUtil,
    hardLimits: HardLimits,
    private val persistenceLayer: PersistenceLayer,
    private val batchExecutor: BatchExecutor,
    private val rxBus: RxBus,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    private val _uiState = MutableStateFlow(TsunamiDialogUiState())
    val uiState: StateFlow<TsunamiDialogUiState> = _uiState.asStateFlow()

    sealed class SideEffect {
        data class ShowDeliveryError(val comment: String) : SideEffect()
        data object ShowNoActionDialog : SideEffect()

        /** The MASTER prepared the batch and returned its merged confirmation [lines]; show them, then [commit] [actionId]. */
        data class ShowConfirmation(val actionId: Long, val lines: List<ConfirmationLine>) : SideEffect()
    }

    private val _sideEffect = MutableSharedFlow<SideEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

    // Ported from the old TsunamiDialog: max tsunami duration = 5 h.
    private val maxDurationMinutes = 5 * 60.0

    init {
        val now = dateUtil.now()
        val pump = activePlugin.activePump
        val constrainedMax = constraintChecker.getMaxBolusAllowed().value()
        val maxInsulin = if (constrainedMax > 0.0) constrainedMax else hardLimits.maxBolus()
        val bolusStep = pump.pumpDescription.bolusStep
        val isTsunamiActive = persistenceLayer.getTsunamiActiveAt(now) != null

        _uiState.update {
            TsunamiDialogUiState(
                insulin = 0.0,
                maxInsulin = maxInsulin,
                bolusStep = bolusStep,
                tsunamiButtonIncrement1 = preferences.get(DoubleKey.TsuButtonIncrement1),
                tsunamiButtonIncrement2 = preferences.get(DoubleKey.TsuButtonIncrement2),
                tsunamiButtonIncrement3 = preferences.get(DoubleKey.TsuButtonIncrement3),
                duration = preferences.get(IntKey.TsuDefaultDuration).toDouble(),
                maxDurationMinutes = maxDurationMinutes,
                isTsunamiActive = isTsunamiActive,
                notes = "",
                eventTime = now,
                showNotesFromPreferences = preferences.get(BooleanKey.OverviewShowNotesInDialogs)
            )
        }
    }

    fun updateInsulin(value: Double) {
        // Old validateInputs(): clamp to maxInsulin (constraint-checked bolus limit).
        val clamped = value.coerceIn(0.0, uiState.value.maxInsulin)
        _uiState.update { it.copy(insulin = clamped) }
    }

    fun addInsulin(increment: Double) {
        val state = uiState.value
        val target = max(0.0, state.insulin + increment).coerceAtMost(state.maxInsulin)
        val newValue = Round.floorTo(target, state.bolusStep)
        _uiState.update { it.copy(insulin = newValue) }
    }

    fun updateDuration(minutes: Int) {
        // Old validateInputs(): duration clamped to 5 h (300 min); negative not meaningful here.
        val clamped = minutes.coerceIn(0, maxDurationMinutes.toInt())
        _uiState.update { it.copy(duration = clamped.toDouble()) }
    }

    fun updateNotes(value: String) {
        _uiState.update { it.copy(notes = value) }
    }

    @Volatile private var confirmedState: TsunamiDialogUiState? = null
    // Distinguishes the OK-button batch (bolus / start-tsunami) from the dedicated Cancel-Tsunami batch,
    // so commit() knows which automation/cleanup branch applies.
    private var pendingIsCancelOnly = false

    /**
     * Tap-confirm (OK button) → ask the MASTER to PREPARE the batch (cap + build the merged confirmation).
     * Mirrors the old submit(): issues a bolus if insulin > 0, and/or starts Tsunami mode if duration > 0.
     * If duration == 0 while Tsunami mode is currently active, this also cancels Tsunami mode (old
     * "issue bolus with Tsu duration == 0 while active" branch). If neither a bolus nor a duration nor an
     * active-cancel applies, surfaces the no-action dialog (old "no action selected" branch).
     */
    fun prepareAndConfirm() {
        appScope.launch {
            val state = uiState.value
            confirmedState = state
            pendingIsCancelOnly = false
            val actions = buildMainActions(state)
            if (actions.isEmpty()) {
                _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
                return@launch
            }
            when (val prepared = batchExecutor.prepare(actions, Sources.TsunamiDialog, rh.gs(app.aaps.core.ui.R.string.tsunami))) {
                is ActionProgress.Prepared -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(prepared.id, prepared.lines))
                is ActionProgress.Rejected -> when (prepared.reason) {
                    FailureReason.NotReachable, FailureReason.ControlDisabled ->
                        rxBus.send(EventShowDialog.Ok(title = rh.gs(app.aaps.core.ui.R.string.tsunami), message = rh.gs(prepared.reason.failTextResId())))
                    FailureReason.NoAction -> _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
                    else -> prepared.detail?.let { detail ->
                        if (config.AAPSCLIENT) rxBus.send(EventShowDialog.Ok(title = rh.gs(app.aaps.core.ui.R.string.tsunami), message = detail))
                        else _sideEffect.tryEmit(SideEffect.ShowDeliveryError(detail))
                    }
                }
                else -> Unit // Unconfirmed → app-level modal
            }
        }
    }

    /**
     * Dedicated Cancel-Tsunami action (old binding.tsuCancel click), independent of the amount/duration
     * fields — always just cancels the currently active Tsunami mode.
     */
    fun prepareCancelTsunami() {
        appScope.launch {
            val state = uiState.value
            confirmedState = state
            pendingIsCancelOnly = true
            val actions = listOf(
                BatchAction.CancelTsunami(notes = state.notes)
            )
            when (val prepared = batchExecutor.prepare(actions, Sources.TsunamiDialog, rh.gs(app.aaps.core.ui.R.string.tsunami))) {
                is ActionProgress.Prepared -> _sideEffect.tryEmit(SideEffect.ShowConfirmation(prepared.id, prepared.lines))
                is ActionProgress.Rejected -> when (prepared.reason) {
                    FailureReason.NotReachable, FailureReason.ControlDisabled ->
                        rxBus.send(EventShowDialog.Ok(title = rh.gs(app.aaps.core.ui.R.string.tsunami), message = rh.gs(prepared.reason.failTextResId())))
                    FailureReason.NoAction -> _sideEffect.tryEmit(SideEffect.ShowNoActionDialog)
                    else -> prepared.detail?.let { detail ->
                        if (config.AAPSCLIENT) rxBus.send(EventShowDialog.Ok(title = rh.gs(app.aaps.core.ui.R.string.tsunami), message = detail))
                        else _sideEffect.tryEmit(SideEffect.ShowDeliveryError(detail))
                    }
                }
                else -> Unit
            }
        }
    }

    /** Confirm the master's prepared batch: deliver/apply the parked bundle exactly once. */
    fun commit(actionId: Long) {
        appScope.launch {
            val result = batchExecutor.commit(actionId, Sources.TsunamiDialog, rh.gs(app.aaps.core.ui.R.string.tsunami))
            // Old code removed the bolus reminder only on a successful bolus (non-cancel-only commit).
            if (result is ActionProgress.Applied && !pendingIsCancelOnly)
                automation.removeAutomationEventBolusReminder()
            if (result is ActionProgress.Rejected) {
                if (result.reason == FailureReason.NotReachable || result.reason == FailureReason.ControlDisabled)
                    rxBus.send(EventShowDialog.Ok(title = rh.gs(app.aaps.core.ui.R.string.tsunami), message = rh.gs(result.reason.failTextResId())))
                else result.detail?.let { detail ->
                    if (config.AAPSCLIENT) rxBus.send(EventShowDialog.Ok(title = rh.gs(app.aaps.core.ui.R.string.tsunami), message = detail))
                    else _sideEffect.tryEmit(SideEffect.ShowDeliveryError(detail))
                }
            }
        }
    }

    /**
     * Build the OK-button batch from dialog state, mirroring the old submit():
     * - insulin > 0 → a bolus (master caps it; never capped client-side).
     * - duration > 0 → start Tsunami mode for that duration.
     * - duration == 0 while Tsunami mode is active → cancel Tsunami mode instead (only if no separate
     *   duration was set — matches the old "cancel on zero-duration bolus" branch).
     */
    private fun buildMainActions(state: TsunamiDialogUiState): List<BatchAction> = buildList {
        val deliverableInsulin = Round.floorTo(state.insulin, state.bolusStep)
        if (deliverableInsulin > 0)
            add(
                BatchAction.Bolus(
                    insulin = deliverableInsulin, carbs = 0, carbsTimeOffsetMinutes = 0, carbsDurationHours = 0,
                    recordOnly = false, notes = state.notes, timestamp = state.eventTime, iCfg = null
                )
            )
        val duration = state.duration.toInt()
        if (duration > 0) {
            add(BatchAction.Tsunami(durationMinutes = duration, notes = state.notes))
        } else if (duration == 0 && state.isTsunamiActive) {
            add(BatchAction.CancelTsunami(notes = state.notes))
        }
    }
}