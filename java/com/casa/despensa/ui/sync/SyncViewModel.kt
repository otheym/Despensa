package com.casa.despensa.ui.sync



import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.casa.despensa.data.ImportSummary
import com.casa.despensa.data.SyncFormatException
import com.casa.despensa.data.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncUiState(
    val isWorking: Boolean = false,
    val lastImport: ImportSummary? = null,
    val lastExportAt: Long? = null,
    val error: String? = null,
)

class SyncViewModel(
    private val repository: SyncRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    /** Genera el JSON a enviar. La pantalla lo guarda en un fichero y abre el menú de compartir. */
    suspend fun buildExport(deviceName: String): String {
        _state.update { it.copy(isWorking = true, error = null) }
        try {
            return repository.export(deviceName)
        } finally {
            _state.update { it.copy(isWorking = false) }
        }
    }

    fun onExported() {
        _state.update { it.copy(lastExportAt = System.currentTimeMillis()) }
    }

    fun importText(text: String) {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null) }
            try {
                val summary = repository.import(text)
                _state.update { it.copy(isWorking = false, lastImport = summary) }
            } catch (e: SyncFormatException) {
                _state.update { it.copy(isWorking = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(isWorking = false, error = "No se pudo importar: ${e.message}") }
            }
        }
    }

    fun onError(message: String) {
        _state.update { it.copy(isWorking = false, error = message) }
    }

    companion object {
        fun factory(repository: SyncRepository) = viewModelFactory {
            initializer { SyncViewModel(repository) }
        }
    }
}