package app.moye

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.moye.data.AppContainer

class MoYeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applyAppLanguage(container.settings.load().languageTag)
    }
}

fun applyAppLanguage(tag: String) {
    val locales = when (tag) {
        "zh" -> LocaleListCompat.forLanguageTags("zh")
        "en" -> LocaleListCompat.forLanguageTags("en")
        else -> LocaleListCompat.getEmptyLocaleList()
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
