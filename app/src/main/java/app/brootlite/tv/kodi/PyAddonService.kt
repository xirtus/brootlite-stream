package app.brootlite.tv.kodi

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors

/**
 * Runs strictly inside the isolated disposable ":pyaddon" process.
 * Zero Python runtime is ever loaded in the main UI/Player process.
 * Self-terminates via Process.killProcess(myPid()) as soon as a video stream is resolved
 * or the user exits AddonBrowserActivity, freeing 100% of Python's ~35 MB footprint.
 */
class PyAddonService : Service() {

    private val pythonExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PyAddonWorker").apply { isDaemon = true }
    }

    private fun ensurePythonStarted(): Python {
        synchronized(PyAddonService::class.java) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }
            return Python.getInstance()
        }
    }

    class PyCallbackBridge(private val remote: IPyDialogCallback?) {
        fun onSelect(heading: String, pyOptions: PyObject?): Int {
            val cb = remote ?: return 0
            val list = pyOptions?.asList()?.map { it.toString() } ?: emptyList()
            if (list.isEmpty()) return -1
            return runCatching { cb.onSelect(heading, list) }.getOrDefault(0)
        }

        fun onInput(heading: String, defaultText: String, hidden: Boolean): String {
            val cb = remote ?: return "__CANCELLED__"
            return runCatching { cb.onInput(heading, defaultText, hidden) ?: "__CANCELLED__" }
                .getOrDefault("__CANCELLED__")
        }

        fun onYesNo(heading: String, message: String, noLabel: String, yesLabel: String): Boolean {
            val cb = remote ?: return true
            return runCatching { cb.onYesNo(heading, message, noLabel, yesLabel) }.getOrDefault(true)
        }

        fun onNotification(heading: String, message: String) {
            runCatching { remote?.onNotification(heading, message) }
        }

        fun onTextBox(heading: String, body: String) {
            runCatching { remote?.onTextBox(heading, body) }
        }
    }

    private val binder = object : IPyAddonService.Stub() {
        override fun executePluginUrl(
            pluginUrl: String,
            kodiEnvDir: String,
            callback: IPyDialogCallback?
        ): String {
            return pythonExecutor.submit<String> {
                val py = ensurePythonStarted()
                val bridgeModule = py.getModule("kodi_bridge")
                val cbWrapper = PyCallbackBridge(callback)
                bridgeModule.callAttr("execute_plugin", pluginUrl, kodiEnvDir, cbWrapper).toString()
            }.get()
        }

        override fun listInstalledVideoAddons(kodiEnvDir: String): String {
            return pythonExecutor.submit<String> {
                val py = ensurePythonStarted()
                val bridgeModule = py.getModule("kodi_bridge")
                bridgeModule.callAttr("list_video_addons", kodiEnvDir).toString()
            }.get()
        }

        override fun killSelfNow() {
            stopSelf()
            Thread {
                runCatching { Thread.sleep(40L) }
                Process.killProcess(Process.myPid())
            }.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Process.killProcess(Process.myPid())
    }
}
