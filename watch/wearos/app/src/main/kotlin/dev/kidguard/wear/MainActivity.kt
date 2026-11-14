package dev.kidguard.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

/** Routes: connect, kids, kid/{i}, where/{i}, msg/{i}. i = index in Store.kids, -1 = the demo kid. */
@Composable
fun App() {
    val ctx = LocalContext.current
    val nav = rememberSwipeDismissableNavController()
    var ver by remember { mutableIntStateOf(0) }   // bumped when the kids list changes
    fun kidAt(i: Int): Kid? = if (i < 0) Kid.DEMO else Store.kids(ctx).getOrNull(i)
    val start = remember { if (Store.kids(ctx).isEmpty()) "connect" else "kids" }
    val iArg = listOf(navArgument("i") { type = NavType.IntType })
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            SwipeDismissableNavHost(navController = nav, startDestination = start) {
                composable("connect") {
                    ConnectScreen(onDemo = { nav.navigate("kid/-1") }) { idx ->
                        ver++
                        nav.navigate("kid/$idx") { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                }
                composable("kids") {
                    KidsScreen(ver, onKid = { nav.navigate("kid/$it") }, onAdd = { nav.navigate("connect") })
                }
                composable("kid/{i}", iArg) { e ->
                    val i = e.arguments?.getInt("i") ?: 0
                    val k = kidAt(i)
                    if (k == null) nav.popBackStack() else KidScreen(
                        k, onWhere = { nav.navigate("where/$i") }, onMessage = { nav.navigate("msg/$i") },
                        onRemove = { Store.removeKid(ctx, i); ver++; nav.navigate("kids") { popUpTo(nav.graph.id) { inclusive = true } } },
                    )
                }
                composable("where/{i}", iArg) { e -> kidAt(e.arguments?.getInt("i") ?: 0)?.let { WhereScreen(it) } }
                composable("msg/{i}", iArg) { e -> kidAt(e.arguments?.getInt("i") ?: 0)?.let { MessageScreen(it) { nav.popBackStack() } } }
            }
        }
    }
}
