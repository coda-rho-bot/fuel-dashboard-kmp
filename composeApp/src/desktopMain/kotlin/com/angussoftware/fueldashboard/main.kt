package com.angussoftware.fueldashboard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.angussoftware.fueldashboard.model.FuelStatusModel
import com.angussoftware.fueldashboard.server.EmbeddedServer
import com.angussoftware.fueldashboard.status.DesktopStatusSurfaces
import com.angussoftware.fueldashboard.ui.FuelDashboardApp
import com.angussoftware.fueldashboard.ui.components.FuelHudContent
import com.angussoftware.fueldashboard.ui.theme.DashboardTheme
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

private const val HEADLESS_FLAG = "--headless"

/**
 * Two ways in, one backend.
 *
 * Without arguments this is the Compose desktop app, unchanged. With
 * [HEADLESS_FLAG] the same [DashboardBackend] runs with no windows at all —
 * server, MCP endpoint, provider polling, history and ingestion — so the
 * orchestrator can be a systemd service on a machine with no display.
 */
fun main(args: Array<String>) {
    // Before anything else: an SLF4J-using library that initializes first
    // pins the logging config for the rest of the process.
    DashboardBackend.configureFileLogging()

    var headless = false
    for (arg in args) {
        when (arg) {
            HEADLESS_FLAG -> headless = true
            // An unrecognized flag is an error, not a no-op. Silently ignoring
            // one means a typo'd `--headles` starts a GUI on a box with no
            // display and the operator is left guessing why the unit failed.
            else -> {
                System.err.println("fuel-dashboard: unknown option '$arg'")
                System.err.println("usage: fuel-dashboard [$HEADLESS_FLAG]")
                kotlin.system.exitProcess(2)
            }
        }
    }

    if (headless) runHeadless() else runGui()
}

/**
 * Runs the backend with no UI and parks until the JVM is asked to stop.
 *
 * Shutdown goes through a hook rather than a signal handler so SIGTERM from
 * `systemctl stop` unwinds the same way a GUI quit does.
 */
private fun runHeadless() {
    val backend = DashboardBackend.start()
    val done = java.util.concurrent.CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("[fuel-dashboard] shutting down")
            backend.shutdown()
            done.countDown()
        },
    )

    // Serving IS the job here. If the port was taken, exit loudly rather than
    // park forever looking healthy — systemd should see a failure, not a
    // process that answers nothing.
    if (!backend.embeddedServer.isRunning) {
        System.err.println(
            "fuel-dashboard: the embedded server did not start — port " +
                "${EmbeddedServer.DEFAULT_PORT} is probably already in use. " +
                "Nothing would be served, so exiting.",
        )
        backend.shutdown()
        kotlin.system.exitProcess(1)
    }

    println(
        "[fuel-dashboard] headless; dashboard on " +
            (backend.embeddedServer.serverUrl ?: "http://127.0.0.1:${EmbeddedServer.DEFAULT_PORT}"),
    )
    // Park the main thread. Every moving part lives on the backend's own
    // scopes, so there is nothing to poll here.
    done.await()
}

private fun runGui() = application {
    val backend = remember { DashboardBackend.start() }
    val viewModel = backend.viewModel
    val themeController = backend.themeController
    val agentManager = backend.agentManager
    val embeddedServer = backend.embeddedServer

    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
    )

    // Reference to the main window — the HUD's double-click brings it to front.
    val mainWindowRef = remember { java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null) }

    Window(
        onCloseRequest = {
            agentManager.stopMonitoring()
            viewModel.close()
            embeddedServer.stop()
            exitApplication()
        },
        title = "Fuel Dashboard",
        icon = appIcon(),
        state = windowState,
    ) {
        DisposableEffect(window) {
            mainWindowRef.set(window)
            onDispose { mainWindowRef.compareAndSet(window, null) }
        }
        DashboardTheme {
            FuelDashboardApp(
                viewModel = viewModel,
                themeController = themeController,
            )
        }
    }

    // ── Status HUD mini-window ────────────────────────────────────────────
    // Compact always-on-top quota/credits glance, themed like the main
    // window. Toggled from Settings → "Status HUD"; visibility observed
    // live so the toggle needs no restart. Undecorated; last position is
    // remembered between runs.
    val hudVisible = DesktopStatusSurfaces.hudVisible
    val hudState = rememberWindowState(
        width = androidx.compose.ui.unit.Dp(
            com.angussoftware.fueldashboard.settings.loadStringSetting(
                com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_WIDTH, "380",
            ).toFloat(),
        ),
        height = androidx.compose.ui.unit.Dp(
            com.angussoftware.fueldashboard.settings.loadStringSetting(
                com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_HEIGHT, "260",
            ).toFloat(),
        ),
        position = androidx.compose.ui.window.WindowPosition(
            androidx.compose.ui.unit.Dp(
                com.angussoftware.fueldashboard.settings.loadStringSetting(
                    com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_X, "1200",
                ).toFloat(),
            ),
            androidx.compose.ui.unit.Dp(
                com.angussoftware.fueldashboard.settings.loadStringSetting(
                    com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_Y, "80",
                ).toFloat(),
            ),
        ),
    )
    if (hudVisible.value) {
        Window(
            onCloseRequest = {
                // Persist the closed state — otherwise the HUD resurrects on
                // next launch despite the user dismissing it.
                DesktopStatusSurfaces().setEnabled(false)
            },
            title = "Fuel Status",
            state = hudState,
            alwaysOnTop = DesktopStatusSurfaces.hudAlwaysOnTop.value,
            undecorated = true,
            transparent = false,
            resizable = true,
        ) {
            // Persist position between runs + enable dragging (undecorated
            // windows have no title bar to grab). Using AWT mouse listeners
            // for window dragging.
            DisposableEffect(window) {
                // Position persistence
                val compListener = object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent?) {
                        com.angussoftware.fueldashboard.settings.saveStringSetting(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_X,
                            hudState.position.x.value.toString(),
                        )
                        com.angussoftware.fueldashboard.settings.saveStringSetting(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_Y,
                            hudState.position.y.value.toString(),
                        )
                    }
                    override fun componentResized(e: java.awt.event.ComponentEvent?) {
                        com.angussoftware.fueldashboard.settings.saveStringSetting(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_WIDTH,
                            hudState.size.width.value.toString(),
                        )
                        com.angussoftware.fueldashboard.settings.saveStringSetting(
                            com.angussoftware.fueldashboard.settings.FuelSettingsKeys.HUD_HEIGHT,
                            hudState.size.height.value.toString(),
                        )
                    }
                }
                window.addComponentListener(compListener)

                // Dragging: record initial click + window position on press,
                // move window on drag.
                var dragStart: java.awt.Point? = null
                var winStart: java.awt.Point? = null
                val mouseListener = object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent?) {
                        e ?: return
                        dragStart = e.locationOnScreen
                        winStart = window.location
                    }
                    override fun mouseDragged(e: java.awt.event.MouseEvent?) {
                        e ?: return
                        val ds = dragStart ?: return
                        val ws = winStart ?: return
                        val dx = e.locationOnScreen.x - ds.x
                        val dy = e.locationOnScreen.y - ds.y
                        window.location = java.awt.Point(ws.x + dx, ws.y + dy)
                    }
                    // Double-click → bring the main dashboard window to front
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        e ?: return
                        if (e.clickCount == 2) {
                            val main = mainWindowRef.get() ?: return
                            if (main is java.awt.Frame && main.state != java.awt.Frame.NORMAL) {
                                main.state = java.awt.Frame.NORMAL // un-minimize
                            }
                            main.toFront()
                            main.requestFocus()
                        }
                    }
                }
                window.addMouseListener(mouseListener)
                window.addMouseMotionListener(mouseListener)

                onDispose {
                    window.removeComponentListener(compListener)
                    window.removeMouseListener(mouseListener)
                    window.removeMouseMotionListener(mouseListener)
                }
            }
            DashboardTheme {
                val state by viewModel.state.collectAsState()
                FuelHudContent(
                    model = FuelStatusModel.from(state),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Window/taskbar icon — loaded once from bundled resources (icon.png). */
private fun appIcon(): androidx.compose.ui.graphics.painter.Painter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader
        .getResourceAsStream("icon.png")?.use { it.readBytes() }
    bytes?.let {
        androidx.compose.ui.graphics.painter.BitmapPainter(
            org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap(),        )
    }
}.getOrNull()

