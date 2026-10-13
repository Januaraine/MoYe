package app.moye

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.moye.core.model.ReaderTheme
import app.moye.core.settings.AppIcon
import app.moye.core.settings.appIconFor

/**
 * Switches the home-screen icon between the day (star) and night (moon) marks.
 * Two launcher aliases target [MainActivity]; the alias that matches the reading
 * theme stays enabled.
 */
object LauncherIcons {
    private const val TAG = "LauncherIcons"
    private const val DAY = "app.moye.LauncherDay"
    private const val NIGHT = "app.moye.LauncherNight"

    fun apply(context: Context, theme: ReaderTheme) {
        val night = appIconFor(theme) == AppIcon.NIGHT
        val pm = context.packageManager
        val day = ComponentName(context.packageName, DAY)
        val nightAlias = ComponentName(context.packageName, NIGHT)
        val enable = if (night) nightAlias else day
        val disable = if (night) day else nightAlias
        try {
            if (!isEnabled(pm, enable, manifestDefault = !night)) {
                pm.setComponentEnabledSetting(
                    enable,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            if (isEnabled(pm, disable, manifestDefault = night)) {
                pm.setComponentEnabledSetting(
                    disable,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not update the launcher icon", error)
        }
    }

    private fun isEnabled(
        pm: PackageManager,
        component: ComponentName,
        manifestDefault: Boolean,
    ): Boolean = when (pm.getComponentEnabledSetting(component)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false
        else -> manifestDefault
    }
}
