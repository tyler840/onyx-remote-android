package com.gsb.onyxremote

import android.util.Log
import com.gsb.onyxremote.model.AppConfig
import com.gsb.onyxremote.model.Defaults
import org.json.JSONObject
import java.io.File

/** config.json in app-private storage, written via a temp file so a crash can't truncate it. */
class ConfigStore(private val dir: File) {
    private val file = File(dir, "config.json")

    fun load(): AppConfig {
        if (!file.exists()) return Defaults.config()
        return try {
            AppConfig.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.e(TAG, "config.json unreadable; keeping a copy and starting fresh", e)
            file.copyTo(File(dir, "config.broken.json"), overwrite = true)
            Defaults.config()
        }
    }

    fun save(config: AppConfig) {
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(config.toJson().toString(2))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val TAG = "ConfigStore"
    }
}
