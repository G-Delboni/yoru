// Preferências do app: endereço do servidor, token, cursor da última sincronia.
// SharedPreferences é o "arquivo de propriedades" do Android. Cada valor também é
// exposto como StateFlow, para a tela reagir quando mudar.
package app.yoru.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("yoru", Context.MODE_PRIVATE)

    private val _address = MutableStateFlow(prefs.getString(KEY_ADDRESS, "") ?: "")
    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, "") ?: "")
    private val _lastSyncAt = MutableStateFlow(prefs.getString(KEY_LAST_SYNC, null))
    private val _lastMessage = MutableStateFlow(prefs.getString(KEY_LAST_MESSAGE, null))

    val address: StateFlow<String> = _address
    val token: StateFlow<String> = _token
    val lastSyncAt: StateFlow<String?> = _lastSyncAt
    val lastMessage: StateFlow<String?> = _lastMessage

    /** Carimbo devolvido pelo servidor na última sincronia; vai como `desde` na próxima. */
    var cursor: String?
        get() = prefs.getString(KEY_CURSOR, null)
        set(value) = prefs.edit().putString(KEY_CURSOR, value).apply()

    /** Tempos do Pomodoro, em minutos. Ficam só neste aparelho. */
    var focusMinutes: Int
        get() = prefs.getInt(KEY_FOCUS, 25)
        set(v) = prefs.edit().putInt(KEY_FOCUS, v.coerceIn(1, 180)).apply()
    var breakMinutes: Int
        get() = prefs.getInt(KEY_BREAK, 5)
        set(v) = prefs.edit().putInt(KEY_BREAK, v.coerceIn(1, 60)).apply()
    var longBreakMinutes: Int
        get() = prefs.getInt(KEY_LONG, 15)
        set(v) = prefs.edit().putInt(KEY_LONG, v.coerceIn(1, 90)).apply()
    var cycles: Int
        get() = prefs.getInt(KEY_CYCLES, 4)
        set(v) = prefs.edit().putInt(KEY_CYCLES, v.coerceIn(1, 12)).apply()

    val isConfigured: Boolean get() = _address.value.isNotBlank() && _token.value.isNotBlank()

    fun saveConnection(address: String, token: String) {
        val clean = normalizeAddress(address)
        val changedServer = clean != _address.value
        prefs.edit().putString(KEY_ADDRESS, clean).putString(KEY_TOKEN, token.trim()).apply()
        _address.value = clean
        _token.value = token.trim()
        if (changedServer) cursor = null  // servidor novo: baixa tudo de novo
    }

    fun recordSync(at: String?, message: String) {
        prefs.edit().putString(KEY_LAST_SYNC, at).putString(KEY_LAST_MESSAGE, message).apply()
        if (at != null) _lastSyncAt.value = at
        _lastMessage.value = message
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_TOKEN = "token"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_FOCUS = "pomodoro_focus"
        private const val KEY_BREAK = "pomodoro_break"
        private const val KEY_LONG = "pomodoro_long"
        private const val KEY_CYCLES = "pomodoro_cycles"

        /** "192.168.0.10:8000" vira "http://192.168.0.10:8000"; tira barra final e espaços. */
        fun normalizeAddress(raw: String): String {
            var a = raw.trim().trimEnd('/')
            if (a.isNotEmpty() && !a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
            return a
        }
    }
}
