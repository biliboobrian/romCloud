package com.romcloud.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.romcloud.app.I18n
import com.romcloud.app.RomCloudApp
import com.romcloud.app.data.Account
import com.romcloud.app.data.PairRequest
import com.romcloud.core.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Écran du profil (téléphone et TV) : connexion, création de compte, déconnexion ; sur la TV,
 * connexion par QR code (validé depuis un téléphone) ; sur le téléphone, validation du QR code
 * d'une TV.
 */
class ProfileViewModel(private val app: RomCloudApp) : ViewModel() {

    /** Code d'une TV scanné ou saisi, en attente de confirmation : appareil qui sera connecté. */
    data class PairConfirm(val code: String, val device: String)

    data class UiState(
        val busy: Boolean = false,
        val error: String? = null,
        /** TV : demande de connexion affichée en QR code. */
        val pair: PairRequest? = null,
        val pairError: String? = null,
        val confirm: PairConfirm? = null,
    )

    val account = app.account.state

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Message ponctuel (bienvenue, déconnexion, TV connectée) : affiché puis effacé. */
    val message = MutableStateFlow<String?>(null)

    private var pairJob: Job? = null

    init {
        viewModelScope.launch { app.account.refresh() }
    }

    private fun run(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: I18n.get(R.string.err_connection)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) return _state.update { it.copy(error = I18n.get(R.string.profile_required)) }
        run {
            app.account.login(username, password)
            message.value = I18n.get(R.string.profile_welcome, username.trim())
        }
    }

    fun register(username: String, password: String, confirm: String) {
        if (username.isBlank() || password.isEmpty()) return _state.update { it.copy(error = I18n.get(R.string.profile_required)) }
        if (password != confirm) return _state.update { it.copy(error = I18n.get(R.string.profile_mismatch)) }
        run {
            app.account.register(username, password)
            message.value = I18n.get(R.string.profile_created, username.trim())
        }
    }

    fun logout() = run {
        app.account.logout()
        message.value = I18n.get(R.string.profile_logged_out)
        if (app.account.isTv) startPairing()
    }

    // ---- TV : connexion par QR code ----

    /** Affiche un QR code et attend sa validation depuis un téléphone (code renouvelé à l'expiration). */
    fun startPairing() {
        if (pairJob?.isActive == true || app.account.state.value.signedIn) return
        pairJob = viewModelScope.launch {
            while (!app.account.state.value.signedIn) {
                val pair = try {
                    app.account.createPair().also { pair -> _state.update { it.copy(pair = pair, pairError = null) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(pair = null, pairError = e.message) }
                    delay(10_000)
                    continue
                }
                while (true) {
                    delay(2_000)
                    val status = runCatching { app.account.pollPair(pair) }.getOrNull() ?: continue
                    if (status.status == "approved") {
                        message.value = I18n.get(R.string.profile_welcome, status.user?.username.orEmpty())
                        _state.update { it.copy(pair = null) }
                        return@launch
                    }
                    if (status.status == "expired") break
                }
            }
        }
    }

    fun stopPairing() {
        pairJob?.cancel()
        pairJob = null
    }

    // ---- Téléphone : validation du QR code d'une TV ----

    /** QR code scanné ou code saisi : demande confirmation avec le nom de l'appareil. */
    fun checkCode(content: String) {
        val code = Account.codeFromQr(content) ?: return _state.update { it.copy(error = I18n.get(R.string.profile_pair_invalid)) }
        run {
            val info = app.account.pairInfo(code)
            _state.update { it.copy(confirm = PairConfirm(code, info.device ?: I18n.get(R.string.profile_pair_unknown_device))) }
        }
    }

    fun cancelPair() = _state.update { it.copy(confirm = null) }

    fun approvePair() {
        val confirm = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        run {
            app.account.approvePair(confirm.code)
            message.value = I18n.get(R.string.profile_pair_done, confirm.device)
        }
    }

    override fun onCleared() {
        stopPairing()
    }
}
