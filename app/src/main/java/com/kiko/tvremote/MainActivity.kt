package com.kiko.tvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvRemoteTheme {
                TvRemoteApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Back from another app: the socket may have died while we were away.
        vm.onForeground()
    }
}
