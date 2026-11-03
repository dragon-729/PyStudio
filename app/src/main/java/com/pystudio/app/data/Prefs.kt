package com.pystudio.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.reflect.KProperty

/** A SharedPreferences-backed property that is also observable by Compose. */
class Pref<T : Any>(private val sp: SharedPreferences, private val key: String, default: T) {
    private val st = mutableStateOf(read(default))

    @Suppress("UNCHECKED_CAST")
    private fun read(d: T): T = when (d) {
        is Boolean -> sp.getBoolean(key, d) as T
        is Int -> sp.getInt(key, d) as T
        is Float -> sp.getFloat(key, d) as T
        else -> (sp.getString(key, d as String) ?: d) as T
    }

    operator fun getValue(thisRef: Any?, p: KProperty<*>): T = st.value

    operator fun setValue(thisRef: Any?, p: KProperty<*>, v: T) {
        st.value = v
        val e = sp.edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Float -> e.putFloat(key, v)
            else -> e.putString(key, v as String)
        }
        e.apply()
    }
}

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("pystudio", Context.MODE_PRIVATE)
    var theme by Pref(sp, "theme", "dark")            // dark | light | system
    var fontSize by Pref(sp, "fontSize", 14f)
    var fontName by Pref(sp, "fontName", "mono")      // mono | serif-mono | custom:<file>
    var tabSize by Pref(sp, "tabSize", 4)
    var lineNumbers by Pref(sp, "lineNumbers", true)
    var symbolBar by Pref(sp, "symbolBar", true)
    var showTabs by Pref(sp, "showTabs", true)
    var autoSave by Pref(sp, "autoSave", true)
    var autoComplete by Pref(sp, "autoComplete", true)
    var linting by Pref(sp, "linting", true)
    var clearOnRun by Pref(sp, "clearOnRun", true)
    var gitName by Pref(sp, "gitName", "")
    var gitEmail by Pref(sp, "gitEmail", "")
    var gitUser by Pref(sp, "gitUser", "")
    var gitToken by Pref(sp, "gitToken", "")
    var recents by Pref(sp, "recents", "")
    var lastProject by Pref(sp, "lastProject", "")
    var openTabs by Pref(sp, "openTabs", "")
    var activeTab by Pref(sp, "activeTab", 0)
    var userSnippets by Pref(sp, "userSnippets", "[]")
}
