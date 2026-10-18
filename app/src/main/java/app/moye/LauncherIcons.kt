package app.moye

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.moye.core.model.Appearance
import app.moye.core.settings.AppIcon
import app.moye.core.settings.appIconFor

/**
 * Switches the home-screen icon between the day (star) and night (moon) marks.
 * [MainActivity] itself stays enabled, so Android Studio can launch it directly.
 * Only one alias is enabled, so the launcher shows a single icon.
 */
object LauncherIcons {
    private const val TAG = "LauncherIcons"
    private const val DAY = "app.moye.LauncherDay"
    private const val NIGHT = "app.moye.LauncherNight"

    fun apply(context: Context, appearance: Appearance) {
        val night = appIconFor(appearance) == AppIcon.NIGHT
        val pm = context.packageManager
        val day = ComponentName(context.packageName, DAY)
        val nightAlias = ComponentName(context.packageName, NIGHT)
        try {
            setEnabled(pm, if (night) nightAlias else day, enabled = true, manifestDefault = !night)
            setEnabled(pm, if (night) day else nightAlias, enabled = false, manifestDefault = night)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not update the launcher icon", error)
        }
    }

    private fun setEnabled(
        pm: PackageManager,
        component: ComponentName,
        enabled: Boolean,
        manifestDefault: Boolean,
    ) {
        if (isEnabled(pm, component, manifestDefault) == enabled) return
        pm.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
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
