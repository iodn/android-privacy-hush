package org.kaijinlab.hush

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : FlutterActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private lateinit var controller: HushController

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        controller = HushController(this)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "org.kaijinlab.hush/control")
            .setMethodCallHandler { call, result ->
                scope.launch {
                    try {
                        val response = mutex.withLock { controller.handle(call.method, call.arguments) }
                        result.success(response)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        result.error("hush", e.message ?: "Action failed", null)
                    }
                }
            }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
