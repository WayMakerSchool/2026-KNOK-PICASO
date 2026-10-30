package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.NoiseApp
import com.example.ui.NoiseViewModel
import com.example.ui.EspRecorderViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: NoiseViewModel by viewModels()
    private val espRecorderViewModel: EspRecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.KNOK_V3) enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(0xFFF7F2EB.toInt(), 0xFFF7F2EB.toInt())
        ) else enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (BuildConfig.KNOK_V3) com.example.ui.v3.KnokV3App(viewModel, espRecorderViewModel)
                    else NoiseApp(
                        viewModel = viewModel,
                        espRecorderViewModel = espRecorderViewModel
                    )
                }
            }
        }
    }

}
