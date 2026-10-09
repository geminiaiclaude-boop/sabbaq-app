package app.qannas

import android.content.Context
import android.content.SharedPreferences
import app.qannas.core.Filters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("qannas_prefs", Context.MODE_PRIVATE)
    }

    var filters: Filters
        get() {
            val str = prefs.getString("filters", null) ?: return Filters()
            return try { Json.decodeFromString(str) } catch (e: Exception) { Filters() }
        }
        set(value) {
            prefs.edit().putString("filters", Json.encodeToString(value)).apply()
        }

    var isActive: Boolean
        get() = prefs.getBoolean("is_active", false)
        set(value) = prefs.edit().putBoolean("is_active", value).apply()
}