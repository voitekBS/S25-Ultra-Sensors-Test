package dev.sensorlab.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.sensorlab.app.ui.theme.SensorLabTheme

/**
 * Single activity. It is a FragmentActivity (not a plain ComponentActivity) because the
 * fingerprint test uses androidx.biometric.BiometricPrompt, which requires one.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SensorLabTheme {
                AppRoot()
            }
        }
    }
}
