package fr.kristenjestin.mue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.kristenjestin.mue.ui.navigation.MueApp
import fr.kristenjestin.mue.ui.theme.MueTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MueTheme {
                MueApp()
            }
        }
    }
}
