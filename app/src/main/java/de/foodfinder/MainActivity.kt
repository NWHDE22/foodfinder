package de.foodfinder

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import de.foodfinder.ui.AppRoot
import de.foodfinder.ui.MainViewModel
import de.foodfinder.ui.theme.FoodFinderTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onLocationPermission(res[Manifest.permission.ACCESS_COARSE_LOCATION] == true || res[Manifest.permission.ACCESS_FINE_LOCATION] == true || vm.repo.locator.hasPermission())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FoodFinderTheme { AppRoot(vm) } }
        if (savedInstanceState == null) {
            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            perms.launch(list.toTypedArray())
        }
    }
}
