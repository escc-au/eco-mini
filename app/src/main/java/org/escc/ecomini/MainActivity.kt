package org.escc.ecomini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.escc.ecomini.ui.ChatScreen
import org.escc.ecomini.ui.EcoTheme
import org.escc.ecomini.ui.SettingsScreen
import org.escc.ecomini.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EcoTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                when (screen) {
                    Screen.Setup -> {
                        if (vm.store.ready()) BackHandler { vm.backToChat() }
                        SetupScreen(vm)
                    }
                    Screen.Chat -> ChatScreen(vm)
                    Screen.Settings -> {
                        BackHandler { vm.closeSettings() }
                        SettingsScreen(vm)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }
}
