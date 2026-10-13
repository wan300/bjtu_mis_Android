package cn.edu.bjtu.mis

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.ui.BjtuMisApp
import cn.edu.bjtu.mis.ui.theme.AppAppearancePreferences
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity() {
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedRoute.value = intent.openRoute()
        val app = application as BjtuMisApplication
        val initialLoadStrategy = if (savedInstanceState == null) {
            ModuleLoadStrategy.CacheFirst
        } else {
            ModuleLoadStrategy.CacheOnly
        }
        setContent {
            var appearance by remember {
                mutableStateOf<AppAppearancePreferences?>(null)
            }
            LaunchedEffect(app.container.themeStore) {
                appearance = app.container.themeStore.initialize()
                app.container.themeStore.appearance.collectLatest {
                    appearance = it
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                app.startDeferredStartupTasks()
            }
            var previewAppearance by remember { mutableStateOf<AppAppearancePreferences?>(null) }
            LaunchedEffect(appearance, previewAppearance) {
                if (previewAppearance != null && previewAppearance == appearance) {
                    previewAppearance = null
                }
            }
            appearance?.let { persistedAppearance ->
                val displayedAppearance = previewAppearance ?: persistedAppearance
                LaunchedEffect(displayedAppearance.uiStyle) {
                    requestedOrientation = when (displayedAppearance.uiStyle) {
                        AppUiStyle.Classic -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        AppUiStyle.Apple -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                BjtuMisTheme(
                    themeOption = displayedAppearance.theme,
                    appearance = displayedAppearance,
                ) {
                    BjtuMisApp(
                        container = app.container,
                        appearance = displayedAppearance,
                        onAppearancePreview = { previewAppearance = it },
                        initialLoadStrategy = initialLoadStrategy,
                        requestedRoute = requestedRoute.value,
                        onRouteHandled = { requestedRoute.value = null },
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = intent.openRoute()
    }

    override fun onStart() {
        super.onStart()
        (application as BjtuMisApplication).container.pluginAutomationSupervisor.onAppVisible()
    }

    override fun onStop() {
        (application as BjtuMisApplication).container.pluginAutomationSupervisor.onAppHidden()
        super.onStop()
    }

    private fun Intent?.openRoute(): String? =
        this?.getStringExtra(EXTRA_OPEN_ROUTE)

    companion object {
        const val EXTRA_OPEN_ROUTE = "cn.edu.bjtu.mis.OPEN_ROUTE"
    }
}
