package de.onesi.hoffnet.smoker.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import de.onesi.hoffnet.smoker.server.ServerController
import de.onesi.hoffnet.smoker.server.SmokerServerFactory
import de.onesi.hoffnet.smoker.store.LocalPreferences
import de.onesi.hoffnet.smoker.store.StateParcelable
import de.onesi.hoffnet.states.OvenState.*
import de.onesi.hoffnet.web.data.Configuration
import de.onesi.hoffnet.web.data.State
import de.onesi.hoffnet.web.data.Temperature
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.plusAssign
import java.net.SocketException
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private val TAG = DefaultMainPresenter::class.java.simpleName

private const val PORT_PATTERN = "\\:\\d{1,5}"
private val DOMAIN_PATTERN = Patterns.DOMAIN_NAME.pattern()
private val IP_PATTERN = Patterns.IP_ADDRESS.pattern()
private val ADDRESS_PATTERN = "$DOMAIN_PATTERN|($IP_PATTERN($PORT_PATTERN)?)".toRegex()

class DefaultMainPresenter @Inject constructor(private val smokerServerFactory: SmokerServerFactory,
                                               private val localPrefs: LocalPreferences) : MainPresenter {

    private var view: MainView? = null
    private var serverController: ServerController? = null

    private var pollingStateDisposable: Disposable? = null
    private var pollingTempDisposable: Disposable? = null
    private val disposables = CompositeDisposable()

    private var lastState: State? = null
    private var lastConfig: Configuration? = null

    override fun bind(view: MainView, state: Bundle?) {
        this.view = view

        localPrefs.lastUsedAddress?.let {
            view.serverAddress = it
            createServerController(it)
        }

        if (state != null) {
            lastState = state.getParcelable<StateParcelable>("last-state").state
        }
    }

    override fun start() {
        view?.activeLoading = false
        view?.enableView(config = false, start = false, stop = false, reload = false)

        lastState?.let { handleState(it) }
    }

    override fun saveState(bundle: Bundle) {
        bundle.putParcelable("last-state", StateParcelable(lastState))
    }

    override fun stop() {
        disposeAll()
    }

    override fun destroy() {
        disposeAll()
        view = null
    }

    override fun onAddressEditorAction(actionId: Int): Boolean {
        return when (actionId) {
            EditorInfo.IME_ACTION_GO -> {
                view?.serverAddress?.let { bindSmokerServer(it) }
                true
            }
            else -> false
        }
    }

    override fun onStartClicked() {
        view?.let { v ->
            val config = Configuration().apply {
                objectTemperature = v.configObjectTemp.toString().toDouble()
                roomTemperature = v.configRoomTemp.toString().toDouble()
                temperatureTolerance = v.configTolerance.toString().toDouble()
                startDate = Date()
            }

            serverController?.let {
                v.enableView(config = false, start = false, stop = false)

                it.configuration(config)
                        .subscribe({
                            handleConfig(it)
                            startPollingState()
                        }, ::handleConfigError)
                        .also { disposables += it }
            }
        }
    }

    override fun onStopClicked() {
        view?.enableView(config = true, start = true, stop = false)
        stopPolling()
    }

    override fun onReloadClicked() {
        view?.enableView(reload = false)
        serverController?.state()
                ?.subscribe(::handleState, ::handleStateError)
                ?.also { disposables += it }
    }

    private fun bindSmokerServer(address: CharSequence) {
        if (ADDRESS_PATTERN matches address) {
            view?.serverAddressInputError = null
            localPrefs.lastUsedAddress = address.toString()
            createServerController(address)
        } else {
            view?.serverAddressInputError = "Das ist keine IP Adresse oder Domain"
        }
    }

    private fun createServerController(address: CharSequence) {
        disposeAll()

        view?.let {
            it.currentState = null
            it.currentObjectTemp = formatTemp(" - ")
            it.currentRoomTemp = formatTemp(" - ")

            it.configObjectTemp = null
            it.configRoomTemp = null
            it.configTolerance = null

            it.enableView(config = false, start = false, stop = false)
        }

        val uri = try {
            Uri.parse("http://$address")
        } catch (e: Exception) {
            view?.currentState = "Fehler: Adresse kann nicht verarbeitet werden."
            null
        }

        if (uri != null) {
            serverController = ServerController(smokerServerFactory.create(uri))

            serverController?.let {
                it.loadingActive
                        .delay {
                            // delay stop signals because ContentLoadingProgessbar is too quick gone
                            Flowable.timer(if (it) 0 else 850, TimeUnit.MILLISECONDS)
                        }
                        .doOnEach { Log.d(TAG, "loading: $it") }
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { view?.activeLoading = it }
                        .also { disposables += it }

                it.state()
                        .subscribe(::handleState, ::handleStateError)
                        .also { disposables += it }
            }
        }
    }

    private fun ensureLatestConfig() {
        if (lastConfig == null) {
            serverController?.let {
                it.configuration()
                        .subscribe(::handleConfig, ::handleConfigError)
                        .also { disposables += it }
            }
        }
    }

    private fun startPollingState() {
        when (pollingStateDisposable?.isDisposed) {
            null, true -> serverController?.let {
                it.pollState()
                        .subscribe(::handleState, ::handleStateError)
                        .also { pollingStateDisposable = it }
            }
        }
    }

    private fun startPollingTemp() {
        when (pollingTempDisposable?.isDisposed) {
            null, true -> serverController?.let {
                it.pollTemperature()
                        .subscribe(::handleTemperature, ::handleTemperatureError)
                        .also { pollingTempDisposable = it }
            }
        }
    }

    private fun stopPolling() {
        pollingStateDisposable?.dispose()
        pollingTempDisposable?.dispose()
    }

    private fun disposeAll() {
        stopPolling()
        disposables.clear()
    }

    private fun handleConfig(config: Configuration) {
        lastConfig = config

        view?.let { v ->
            v.configObjectTemp = config.objectTemperature?.toString()
            v.configRoomTemp = config.roomTemperature?.toString()
            v.configTolerance = config.temperatureTolerance?.toString()
        }
    }

    private fun handleConfigError(e: Throwable) {
        Log.w(TAG, "config error", e)

        lastConfig = null
        view?.let { v ->
            v.currentState = "Fehler: ${e.message}"

            v.configObjectTemp = null
            v.configRoomTemp = null
            v.configTolerance = null
        }
    }

    private fun handleState(state: State) {
        lastState = state

        view?.currentState = state.ovenState.toString()
        view?.enableView(reload = false)

        state.ovenState?.let {
            when (it) {
                INITIALIZE -> {
                    startPollingState()

                    view?.let {
                        // waiting for the next poll
                        it.activeLoading = true
                        it.enableView(config = false, start = false, stop = false)
                    }
                }

                READY -> {
                    stopPolling()

                    serverController?.let {
                        it.configuration()
                                .doOnRequest { view?.activeLoading = true }
                                .subscribe({
                                    handleConfig(it)

                                    view?.let {
                                        it.activeLoading = false
                                        it.enableView(config = true, start = true, stop = false)
                                    }
                                }, ::handleConfigError)
                                .also { disposables.add(it) }
                    }
                }

                PREPAIRE, PREPAIRE_NOTHING, PREPAIRE_WAIT -> {
                    startPollingState()
                    ensureLatestConfig()

                    view?.let {
                        // waiting for the next poll
                        it.activeLoading = true
                        it.enableView(config = false, start = false, stop = false)
                    }
                }

                START, BUSY, HEATING, COOLING, SMOKE -> {
                    startPollingState()
                    startPollingTemp()
                    ensureLatestConfig()

                    view?.enableView(stop = true)
                }

                AIR -> {
                    startPollingState()
                    startPollingTemp()
                    ensureLatestConfig()

                    Calendar.getInstance().apply {
                        time = state.timestamp
                        add(Calendar.MINUTE, 60)

                        view?.currentState = "Spähne nachfüllen bis ${get(Calendar.HOUR_OF_DAY)}:${get(Calendar.MINUTE)} Uhr"
                    }
                }

                FAILED -> {
                    stopPolling()
                    startPollingState()

                    view?.let {
                        it.currentState = "Fehler: ${state.message}"

                        // waiting for the next poll
                        it.activeLoading = true
                        it.enableView(config = false, start = false, stop = false)
                    }
                }

                FINISHED -> {
                    stopPolling()
                    view?.enableView(config = true, start = true, stop = false)
                }
            }
        }
    }

    private fun handleStateError(e: Throwable) {
        Log.w(TAG, "state error", e)

        lastState = null

        view?.enableView(config = false, start = false, stop = false, reload = true)

        when (e) {
            is SocketException, is UnknownHostException -> {
                view?.currentState = "Smoker nicht erreichbar"
            }

            else -> {
                view?.currentState = "Fehler: ${e.message}"
            }
        }

        view?.enableView(config = false, start = false, stop = false)
    }

    private fun handleTemperature(temperature: Temperature) {
        view?.currentObjectTemp = formatTemp(temperature.objectTemperature)
        view?.currentRoomTemp = formatTemp(temperature.roomTemperature)
    }

    private fun handleTemperatureError(e: Throwable) {
        Log.w(TAG, "temperature error", e)

        view?.currentObjectTemp = "-"
        view?.currentRoomTemp = "-"
    }

    private fun formatTemp(value: Double) = "%.1f °C".format(value)
    private fun formatTemp(value: CharSequence) = "$value °C"

}