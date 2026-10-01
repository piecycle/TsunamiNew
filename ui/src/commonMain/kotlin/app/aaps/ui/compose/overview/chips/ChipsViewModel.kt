package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.SensitivityOverview
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventConfigBuilderChange
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.extensions.displayText
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Stable
@AssistedInject
class ChipsViewModel(
    @Assisted cache: OverviewDataCache,
    private val iobCobCalculator: IobCobCalculator,
    private val loop: Loop,
    private val config: Config,
    private val persistenceLayer: PersistenceLayer,
    private val sensitivityOverview: SensitivityOverview,
    private val rh: TextResolver,
    private val decimalFormatter: DecimalFormatter,
    private val rxBus: RxBus,
    private val activePlugin: ActivePlugin,
    private val dateUtil: DateUtil
) : ViewModel() {

    @AssistedFactory
    interface Factory {

        fun create(cache: OverviewDataCache): ChipsViewModel
    }

    private val iobCobTicker = flow {
        while (true) {
            emit(Unit)
            delay(60_000L) // Revert to 2.5 minutes refresh // TSUNAMI: Changed from 150_000 to 60_000 to trigger more frequent updates for Tsunami chip
        }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    private val tsuRefreshFlow = rxBus.toFlow(EventRefreshOverview::class)

    // Switching the active APS plugin doesn't fire EventRefreshOverview, so without this the
    // Tsunami chip's visibility (and the IOB/COB icon layout that depends on its width) would
    // lag behind the switch until the next iobCobTicker tick or an actual loop run.
    private val configChangeFlow = rxBus.toFlow(EventConfigBuilderChange::class)

    val iobUiState: StateFlow<IobUiState> = iobCobTicker.combine(cache.iobGraphFlow) { _, _ ->
        val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
        val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
        val total = bolusIob.iob + basalIob.basaliob
        IobUiState(
            text = rh.gs(InterfacesStrings.format_insulin_units, total),
            iobTotal = total
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = IobUiState()
    )

    val cobUiState: StateFlow<CobUiState> = iobCobTicker.combine(cache.cobGraphFlow) { _, _ ->
        val cobInfo = iobCobCalculator.getCobInfo("ChipsViewModel COB")
        var cobText = cobInfo.displayText(rh, decimalFormatter)
            ?: rh.gs(CoreUiStrings.value_unavailable_short)
        var carbsReq = 0

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText += " ${constraintsProcessed.carbsReq}${rh.gs(CoreUiStrings.required)}"
                }
                carbsReq = constraintsProcessed.carbsReq
            }
        }

        CobUiState(text = cobText, carbsReq = carbsReq, cobValue = cobInfo.displayCob ?: 0.0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CobUiState()
    )

    // The IOB graph is published before the loop runs in the same calculation chain, so on its
    // own it would show the previous loop's ratio and variable ISF. Predictions are published
    // right after the loop ran on the master, and right after a device status came in on a
    // client, so they carry the fresh values on both sides.
    val sensitivityUiState: StateFlow<SensitivityUiState> = combine(iobCobTicker, cache.iobGraphFlow, cache.predictionsFlow) { _, _, _ ->
        buildSensitivityUiState()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensitivityUiState()
    )

    val tsuUiState: StateFlow<TsuUiState> = combine(
        iobCobTicker,
        tsuRefreshFlow.onStart { emit(EventRefreshOverview("init")) },
        configChangeFlow.onStart { emit(EventConfigBuilderChange()) }
    ) { _, _, _ ->
        val isTsunamiSelected = activePlugin.activeAPS?.algorithm == APSResult.Algorithm.TSUNAMI
        val now = dateUtil.now()
        val activeTsunami = persistenceLayer.getTsunamiActiveAt(now)
        if (isTsunamiSelected && activeTsunami != null) {
            val remaining = (activeTsunami.timestamp + activeTsunami.duration - now) / 60000L
            TsuUiState(
                isVisible = true,
                isActive = true,
                text = "${remaining}m",
                duration = "${remaining}m"
            )
        } else {
            TsuUiState(isVisible = isTsunamiSelected, isActive = false, text = "n/a", duration = "n/a")
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TsuUiState()
    )

    private suspend fun buildSensitivityUiState(): SensitivityUiState {
        // Worked out in one place for the chip, its dialog and the watch - see SensitivityOverview
        val data = sensitivityOverview.build()
        return SensitivityUiState(
            asText = data.asText,
            isfFrom = data.isfFrom,
            isfTo = data.isfTo,
            dialogText = data.lines.joinToString("\n"),
            ratio = data.ratio,
            isEnabled = data.isEnabled,
            hasData = data.hasData
        )
    }

    fun showIobInfo() {
        viewModelScope.launch {
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val total = bolusIob.iob + basalIob.basaliob
            val message =
                rh.gs(CoreUiStrings.bolus_iob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, bolusIob.iob) + "\n" +
                    rh.gs(CoreUiStrings.treatments_wizard_basaliob_label) + ": " + rh.gs(InterfacesStrings.format_insulin_units, basalIob.basaliob) + "\n" +
                    rh.gs(CoreUiStrings.iob) + ": " + rh.gs(InterfacesStrings.format_insulin_units, total)
            rxBus.send(
                EventShowDialog.Ok(
                    title = rh.gs(CoreUiStrings.iob),
                    message = message
                )
            )
        }
    }
}
