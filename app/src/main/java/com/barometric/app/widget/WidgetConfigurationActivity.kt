package com.barometric.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.barometric.app.ui.BarometricTheme
import kotlinx.coroutines.launch

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        setContent { BarometricTheme {
            var mode by remember { mutableStateOf(WidgetSettings.mode(this, id)) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Pressure widget", style = MaterialTheme.typography.headlineMedium)
                    Text("Choose what to show. The widget uses your app's location and pressure units.")
                    WidgetMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable { mode = option }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == option, onClick = { mode = option })
                            Text(option.label, Modifier.padding(start = 8.dp))
                        }
                    }
                    Text("The graph overlays all available sources. Tap the widget to inspect readings in the app. Resize it for more room.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = !saving, onClick = {
                        saving = true
                        lifecycleScope.launch {
                            try {
                                WidgetSettings.set(this@WidgetConfigurationActivity, id, mode)
                                PressureWidget.update(this@WidgetConfigurationActivity, intArrayOf(id))
                                PressureWidget.requestRefresh(this@WidgetConfigurationActivity)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            } catch (_: Exception) { error = "Couldn’t save the widget. Try again."; saving = false }
                        }
                    }) { Text("Save widget") }
                    TextButton(onClick = { finish() }, enabled = !saving) { Text("Cancel") }
                }
            }
        } }
    }
}
