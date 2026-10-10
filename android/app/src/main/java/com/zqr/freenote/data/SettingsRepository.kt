package com.zqr.freenote.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Read/write abstraction over persistent app settings.
 *
 * Screens and other consumers should depend on this interface instead of
 * reaching into [SettingsManager] directly, so that the storage mechanism can
 * be swapped or mocked without touching UI code. There is no DI framework in
 * place, so a ready-to-use default implementation is exposed via
 * [DefaultSettingsRepository.instance]; it simply delegates to the existing
 * [SettingsManager] singleton and therefore preserves current behavior.
 */
interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setThemeMode(mode: ThemeMode)
    fun setDynamicColor(enabled: Boolean)
    fun setPin(pin: String)
    fun clearPin()
    fun verifyPin(pin: String): Boolean
    fun hasPin(): Boolean
}

/**
 * Default [SettingsRepository] that forwards every call to the [SettingsManager]
 * singleton. The shared [settings] flow is the exact instance published by
 * [SettingsManager], so observers continue to see identical state updates.
 */
class DefaultSettingsRepository : SettingsRepository {
    override val settings: StateFlow<AppSettings>
        get() = SettingsManager.settings

    override fun setThemeMode(mode: ThemeMode) = SettingsManager.setThemeMode(mode)
    override fun setDynamicColor(enabled: Boolean) = SettingsManager.setDynamicColor(enabled)
    override fun setPin(pin: String) = SettingsManager.setPin(pin)
    override fun clearPin() = SettingsManager.clearPin()
    override fun verifyPin(pin: String): Boolean = SettingsManager.verifyPin(pin)
    override fun hasPin(): Boolean = SettingsManager.hasPin()

    companion object {
        /**
         * Process-wide default instance used when no explicit repository is
         * provided (e.g. via the defaulted Composable parameters).
         */
        val instance: SettingsRepository = DefaultSettingsRepository()
    }
}
