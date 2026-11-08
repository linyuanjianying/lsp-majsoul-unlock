package com.yefeng.majmax.hookprobe.manager

import android.content.Context

/** Independent persisted switch for the fixed, click-through recommendation and score panel. */
internal object RecommendationPanelSettings {
    private const val PREFS = "recommendation-panel"
    private const val KEY_ENABLED = "enabled"

    /** 当前是否显示推荐与番点面板；读取失败时回落到默认值 `true`。 */
    @JvmStatic
    fun read(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        }.getOrDefault(true)

    /**
     * 持久化开关。只写偏好，不触碰任何其他功能：
     * 不启停服务、不改无人值守 / 自动下一局、不改显示模式，也不影响猫头提示。
     *
     * @return 写入后重新读回的真实值；调用方应以它为准（而不是以请求值为准），
     *         这样写入失败时界面不会显示一个并未保存的状态。
     */
    @JvmStatic
    fun save(context: Context, enabled: Boolean): Boolean {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
        return read(context)
    }
}
