package uz.monitorcheck.data

import android.content.Context

/** t_row kalibrovkasini qurilmada saqlash (kamera + tasvir o'lchami bo'yicha). */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)

    fun tRowSec(key: String): Double? =
        prefs.getString("trow:$key", null)?.toDoubleOrNull()

    fun save(key: String, tRowSec: Double) {
        prefs.edit().putString("trow:$key", tRowSec.toString()).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove("trow:$key").apply()
    }

    var useCalibration: Boolean
        get() = prefs.getBoolean("use", true)
        set(v) { prefs.edit().putBoolean("use", v).apply() }
}
