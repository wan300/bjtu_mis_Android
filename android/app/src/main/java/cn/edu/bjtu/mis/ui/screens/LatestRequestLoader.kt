package cn.edu.bjtu.mis.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class LatestRequestLoader<T>(
    private val scope: CoroutineScope,
    private val onResult: (Result<T>) -> Unit,
) {
    private var version = 0L

    fun invalidate() {
        version++
    }

    // Keep shared repository requests alive; only the latest UI request may publish.
    fun load(block: suspend () -> T) {
        val requestVersion = ++version
        scope.launch {
            val result = try {
                Result.success(block())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Result.failure(error)
            }
            currentCoroutineContext().ensureActive()
            if (requestVersion == version) onResult(result)
        }
    }
}
