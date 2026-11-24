package de.foodfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.DeliveryDining
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import de.foodfinder.data.Mode

enum class Tab(val label: String, val icon: ImageVector, val iconSel: ImageVector) {
    DELIVERY("Liefern", Icons.Outlined.DeliveryDining, Icons.Filled.DeliveryDining),
    PICKUP("Abholen", Icons.Outlined.ShoppingBag, Icons.Filled.ShoppingBag),
    PLACES("Orte", Icons.Outlined.Place, Icons.Filled.Place),
    SETTINGS("Konten", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val detail by vm.detail.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DELIVERY) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.messageShown() }
    }

    if (detail != null) {
        BackHandler { vm.closeDetail() }
        DetailScreen(detail!!, state.persons, onPersons = vm::setPersons, onBack = vm::closeDetail)
        return
    }
    BackHandler(enabled = tab != Tab.DELIVERY) { tab = Tab.DELIVERY }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            if (t == Tab.DELIVERY) vm.setMode(Mode.DELIVERY)
                            if (t == Tab.PICKUP) vm.setMode(Mode.PICKUP)
                        },
                        icon = { Icon(if (tab == t) t.iconSel else t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab",
            modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) { t ->
            when (t) {
                Tab.DELIVERY -> DealsScreen(vm, state, Mode.DELIVERY, onPlaces = { tab = Tab.PLACES })
                Tab.PICKUP -> DealsScreen(vm, state, Mode.PICKUP, onPlaces = { tab = Tab.PLACES })
                Tab.PLACES -> PlacesScreen(vm, state)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
