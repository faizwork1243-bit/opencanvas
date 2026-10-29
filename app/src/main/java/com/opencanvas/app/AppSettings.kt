package com.opencanvas.app

import android.content.Context
import android.content.SharedPreferences

/** App-wide options, saved on the phone and read live by the canvas. */
object AppSettings {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("carnival_settings", Context.MODE_PRIVATE)
        }
    }

    private fun bool(key: String, def: Boolean): Boolean = prefs?.getBoolean(key, def) ?: def
    private fun int(key: String, def: Int): Int = prefs?.getInt(key, def) ?: def
    private fun flt(key: String, def: Float): Float = prefs?.getFloat(key, def) ?: def
    private fun putBool(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }
    private fun putInt(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }
    private fun putFlt(key: String, v: Float) { prefs?.edit()?.putFloat(key, v)?.apply() }

    var haptics: Boolean
        get() = bool("haptics", true)
        set(v) = putBool("haptics", v)

    var twoFingerUndo: Boolean
        get() = bool("twoFingerUndo", true)
        set(v) = putBool("twoFingerUndo", v)

    var threeFingerRedo: Boolean
        get() = bool("threeFingerRedo", true)
        set(v) = putBool("threeFingerRedo", v)

    var quickShape: Boolean
        get() = bool("quickShape", true)
        set(v) = putBool("quickShape", v)

    /** How long to hold still before a stroke snaps to a shape, in milliseconds. */
    var holdDelayMs: Int
        get() = int("holdDelayMs", 500)
        set(v) = putInt("holdDelayMs", v)

    var eyedropper: Boolean
        get() = bool("eyedropper", true)
        set(v) = putBool("eyedropper", v)

    var pickDelayMs: Int
        get() = int("pickDelayMs", 550)
        set(v) = putInt("pickDelayMs", v)

    /** When on, only a stylus draws; a finger pans and zooms. */
    var stylusOnly: Boolean
        get() = bool("stylusOnly", false)
        set(v) = putBool("stylusOnly", v)

    fun smudge(): SmudgeSettings {
        val d = SmudgeSettings()
        return SmudgeSettings(
            persistence = flt("sm_persistence", d.persistence),
            hardness = flt("sm_hardness", d.hardness),
            spacing = flt("sm_spacing", d.spacing),
            sizePressure = flt("sm_sizePressure", d.sizePressure),
            strengthPressure = flt("sm_strengthPressure", d.strengthPressure),
            soften = bool("sm_soften", d.soften)
        )
    }

    fun setSmudge(s: SmudgeSettings) {
        putFlt("sm_persistence", s.persistence)
        putFlt("sm_hardness", s.hardness)
        putFlt("sm_spacing", s.spacing)
        putFlt("sm_sizePressure", s.sizePressure)
        putFlt("sm_strengthPressure", s.strengthPressure)
        putBool("sm_soften", s.soften)
    }
}
