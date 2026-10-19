package com.barometric.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.barometric.app.ui.*

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels { AppViewModel.Factory((application as BarometricApplication).container) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BarometricTheme { BarometricApp(model) } }
    }
    override fun onResume() { super.onResume(); model.foreground() }
}

class PermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BarometricTheme { PrivacyScreen { finish() } } }
    }
}
