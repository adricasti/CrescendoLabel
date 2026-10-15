package com.crescendo.label.ui.main

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crescendo.label.CrescendoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MainScreenUiState {
    data class Prompt(val nfcEnabled: Boolean = true) : MainScreenUiState
    data object Reading : MainScreenUiState
    data class Success(
        val deviceName: String,
        val aaguid: String?,
        val serialNumber: String,
        val appletVersion: String
    ) : MainScreenUiState
    data class Error(val message: String) : MainScreenUiState
}

class MainScreenViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Prompt(true))
    val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

    fun updateNfcStatus(enabled: Boolean) {
        if (_uiState.value is MainScreenUiState.Prompt) {
            _uiState.value = MainScreenUiState.Prompt(nfcEnabled = enabled)
        }
    }

    fun resetToPrompt() {
        _uiState.value = MainScreenUiState.Prompt(true)
    }

    fun onTagDiscovered(tag: Tag) {
        viewModelScope.launch {
            _uiState.value = MainScreenUiState.Reading
            val result = withContext(Dispatchers.IO) {
                processTag(tag)
            }
            _uiState.value = result
        }
    }

    private fun processTag(tag: Tag): MainScreenUiState {
        val isoDep = IsoDep.get(tag) ?: return MainScreenUiState.Error("This does not seem to be a Crescendo device")

        return try {
            isoDep.connect()
            isoDep.timeout = 5000 // 5 seconds timeout for smartcard crypto/APDU

            // APDU Command 1: 00A4040000
            val cmd1 = CrescendoParser.hexToBytes("00A4040000")
            val resp1 = isoDep.transceive(cmd1)
            Log.d("CrescendoNfc", "CMD1 > 00A4040000 | RESP < ${resp1.joinToString("") { "%02X".format(it) }}")
            if (!CrescendoParser.isStatus9000(resp1)) {
                return MainScreenUiState.Error("This does not seem to be a Crescendo device")
            }

            // APDU Command 2: 80CA9F7F00
            val cmd2 = CrescendoParser.hexToBytes("80CA9F7F00")
            val resp2 = isoDep.transceive(cmd2)
            Log.d("CrescendoNfc", "CMD2 > 80CA9F7F00 | RESP < ${resp2.joinToString("") { "%02X".format(it) }}")
            if (!CrescendoParser.isStatus9000(resp2)) {
                return MainScreenUiState.Error("This does not seem to be a Crescendo device")
            }

            // APDU Command 3: 00A4040007A000000079100000
            val cmd3 = CrescendoParser.hexToBytes("00A4040007A000000079100000")
            val resp3 = isoDep.transceive(cmd3)
            Log.d("CrescendoNfc", "CMD3 > 00A4040007A000000079100000 | RESP < ${resp3.joinToString("") { "%02X".format(it) }}")
            if (!CrescendoParser.isStatus9000(resp3)) {
                return MainScreenUiState.Error("This does not seem to be a Crescendo device")
            }

            // APDU Command 4: 00A4040008A0000006472F000100 (Select FIDO Application)
            val cmd4 = CrescendoParser.hexToBytes("00A4040008A0000006472F000100")
            val resp4 = isoDep.transceive(cmd4)
            Log.d("CrescendoNfc", "CMD4 > 00A4040008A0000006472F000100 | RESP < ${resp4.joinToString("") { "%02X".format(it) }}")
            if (!CrescendoParser.isStatus9000(resp4)) {
                return MainScreenUiState.Error("This does not seem to be a Crescendo device")
            }

            // APDU Command 5: 80100000010400 (authenticatorGetInfo)
            val cmd5 = CrescendoParser.hexToBytes("80100000010400")
            val resp5 = isoDep.transceive(cmd5)
            Log.d("CrescendoNfc", "CMD5 > 80100000010400 | RESP < ${resp5.joinToString("") { "%02X".format(it) }}")
            if (!CrescendoParser.isStatus9000(resp5) || resp5.isEmpty() || resp5[0] != 0x00.toByte()) {
                return MainScreenUiState.Error("This does not seem to be a Crescendo device")
            }

            // Extract serial number, applet version, and AAGUID
            val serial = CrescendoParser.extractSerialNumber(resp2)
            val version = CrescendoParser.extractAppletVersion(resp3)
            val aaguid = CrescendoParser.extractAaguid(resp5)

            if (serial == null || version == null) {
                MainScreenUiState.Error("This does not seem to be a Crescendo device")
            } else {
                val deviceName = CrescendoParser.resolveDeviceName(aaguid)
                MainScreenUiState.Success(
                    deviceName = deviceName,
                    aaguid = aaguid,
                    serialNumber = serial,
                    appletVersion = version
                )
            }
        } catch (e: Exception) {
            Log.e("CrescendoNfc", "Transceive error", e)
            MainScreenUiState.Error("This does not seem to be a Crescendo device")
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {}
        }
    }
}
