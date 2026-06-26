package pt.cuco.scanner

import android.content.Context
import android.net.Uri

/**
 * Holds the configurable parts of the CUCo URL so the app keeps working when
 * Inforlandia changes the link (it has changed several times). Persisted in
 * SharedPreferences and editable from [SettingsActivity].
 *
 * Current expected URL shape:
 * `https://cuco.inforlandia.pt/ucode/?client=jpik_tipo1&lang=pt&l=<machineId>`
 * where `l` is the machine serial number (machine id).
 */
object SettingsRepository {

    const val DEFAULT_BASE_URL = "https://cuco.inforlandia.pt/ucode/"
    const val DEFAULT_CLIENT = "jpik_tipo1"
    const val DEFAULT_LANG = "pt"

    private const val PREFS_NAME = "cuco_settings"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_CLIENT = "client"
    private const val KEY_LANG = "lang"

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL)
            ?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL

    fun client(context: Context): String =
        prefs(context).getString(KEY_CLIENT, DEFAULT_CLIENT)
            ?.ifBlank { DEFAULT_CLIENT } ?: DEFAULT_CLIENT

    fun lang(context: Context): String =
        prefs(context).getString(KEY_LANG, DEFAULT_LANG)
            ?.ifBlank { DEFAULT_LANG } ?: DEFAULT_LANG

    fun save(context: Context, baseUrl: String, client: String, lang: String) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString(KEY_CLIENT, client.trim().ifBlank { DEFAULT_CLIENT })
            .putString(KEY_LANG, lang.trim().ifBlank { DEFAULT_LANG })
            .apply()
    }

    fun resetToDefaults(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Parses a full CUCo URL (as pasted/scanned by the user) into its pieces so
     * the app can adapt to a new link format without a code change. Returns null
     * if the string is not a usable CUCo URL.
     */
    fun parseCucoUrl(raw: String): ParsedUrl? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme == null || uri.host == null) return null

        val base = buildString {
            append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) append(":").append(uri.port)
            val path = uri.path.orEmpty()
            append(if (path.isBlank()) "/" else path)
        }
        return ParsedUrl(
            baseUrl = base,
            client = uri.getQueryParameter("client")?.takeIf { it.isNotBlank() },
            lang = uri.getQueryParameter("lang")?.takeIf { it.isNotBlank() },
            machineId = uri.getQueryParameter("l")?.takeIf { it.isNotBlank() },
        )
    }

    data class ParsedUrl(
        val baseUrl: String,
        val client: String?,
        val lang: String?,
        val machineId: String?,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
