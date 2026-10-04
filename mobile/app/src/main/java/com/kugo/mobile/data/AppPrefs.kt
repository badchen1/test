package com.kugo.mobile.data

import android.content.Context

/** 主题模式。System = 跟随系统。 */
enum class ThemeMode { System, Light, Dark }

/**
 * 轻量 UI 偏好。
 *
 * 与 [KeyStore] 分开：这里存的是「界面怎么显示」，不是凭据，没有加密必要，
 * 用 SharedPreferences 足够；也不该混进密钥目录。
 */
class AppPrefs private constructor(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.values().firstOrNull { it.name == sp.getString(KEY_THEME, null) }
            ?: ThemeMode.System
        set(value) {
            sp.edit().putString(KEY_THEME, value.name).apply()
        }

    /**
     * 发布到自定义目录后，是否删掉应用私有目录里的那份副本。
     *
     * 默认删（省空间）。用户如果担心 SAF 目标盘是移动存储、随时会被拔掉，
     * 可以关掉它留一份兜底。
     */
    var deletePrivateCopyAfterPublish: Boolean
        get() = sp.getBoolean(KEY_DELETE_PRIVATE_COPY, true)
        set(value) {
            sp.edit().putBoolean(KEY_DELETE_PRIVATE_COPY, value).apply()
        }

    /**
     * 是否已经同意过免责声明。
     *
     * 默认 false，即首次启动必须过那道确认门。同意后不再打扰，
     * 但设置页里始终留着一个可以随时重看全文的入口。
     * 只存一个布尔值，不做「几时同意的」这类记录——那属于溯源数据，
     * 与这个应用「不采集任何信息」的定位不符。
     */
    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
        set(value) {
            sp.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, value).apply()
        }

    companion object {
        private const val FILE = "kugo_app_prefs"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DELETE_PRIVATE_COPY = "delete_private_copy"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}
