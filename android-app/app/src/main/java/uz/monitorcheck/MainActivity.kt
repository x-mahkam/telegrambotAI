package uz.monitorcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import uz.monitorcheck.ui.flicker.FlickerScreen
import uz.monitorcheck.ui.home.HomeScreen
import uz.monitorcheck.ui.theme.MonitorCheckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonitorCheckTheme { MonitorCheckApp() }
        }
    }
}

/** Ilova navigatsiyasi. Keyingi bosqichlarda yangi modullar shu yerga qo'shiladi. */
@Composable
fun MonitorCheckApp() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(onOpenFlicker = { nav.navigate("flicker") })
        }
        composable("flicker") {
            FlickerScreen(onBack = { nav.popBackStack() })
        }
    }
}
