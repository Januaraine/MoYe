package app.moye

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.moye.core.input.KeyMapper
import app.moye.core.model.ReaderCommand
import app.moye.ui.reader.ReaderScreen
import app.moye.ui.shelf.ShelfScreen
import app.moye.ui.theme.MoYeTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

object ReaderKeyBridge {
    var handler: ((ReaderCommand) -> Boolean)? = null
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MoYeTheme {
                MoYeApp()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val command = KeyMapper.map(event.keyCode)
            if (command != null) {
                if (command == ReaderCommand.TOGGLE_PLAYBACK && event.repeatCount > 0) {
                    return true
                }
                if (ReaderKeyBridge.handler?.invoke(command) == true) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun MoYeApp() {
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val bookId = openBookId
    if (bookId == null) {
        ShelfScreen(onOpenBook = { openBookId = it })
    } else {
        ReaderScreen(bookId = bookId, onBack = { openBookId = null })
    }
}

@Composable
fun ObserveResume(onResume: () -> Unit, onPause: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, onResume, onPause) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
