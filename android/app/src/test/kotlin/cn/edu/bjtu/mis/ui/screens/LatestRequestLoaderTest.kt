package cn.edu.bjtu.mis.ui.screens

import java.io.IOException
import cn.edu.bjtu.mis.data.network.SingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestRequestLoaderTest {
    @Test
    fun dismissingReadmeInvalidatesThePendingResponse() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val loader = LatestRequestLoader<String>(this) { results += it.getOrThrow() }
        loader.load {
            started.complete(Unit)
            release.await()
            finished.complete(Unit)
            "dismissed README"
        }
        started.await()
        loader.invalidate()
        release.complete(Unit)
        finished.await()
        yield()

        assertEquals(emptyList<String>(), results)
    }

    @Test
    fun reopenedReadmeCannotBeOverwrittenByItsEarlierRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val loader = LatestRequestLoader<String>(this) {
            results += it.getOrThrow()
            published.complete(Unit)
        }
        loader.load {
            started.complete(Unit)
            release.await()
            finished.complete(Unit)
            "old README"
        }
        started.await()
        loader.invalidate()
        loader.load { "current README" }
        published.await()
        release.complete(Unit)
        finished.await()
        yield()

        assertEquals(listOf("current README"), results)
    }

    @Test
    fun repeatedQueryCanShareTheInFlightRepositoryRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val singleFlight = SingleFlight()
        var networkCalls = 0
        val results = mutableListOf<String>()
        val loader = LatestRequestLoader<String>(this) {
            results += it.getOrThrow()
            published.complete(Unit)
        }
        val query: suspend () -> String = {
            singleFlight.run("same-query") {
                networkCalls++
                started.complete(Unit)
                release.await()
                "rooms"
            }
        }
        loader.load(query)
        started.await()
        loader.load(query)
        yield()
        release.complete(Unit)
        published.await()
        assertEquals(1, networkCalls)
        assertEquals(listOf("rooms"), results)
    }

    @Test
    fun obsoleteSuccessAndFailureCannotReplaceLatestResult() = runBlocking {
        for (failOldRequest in listOf(false, true)) {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val newPublished = CompletableDeferred<Unit>()
            val oldFinished = CompletableDeferred<Unit>()
            val results = mutableListOf<Result<String>>()
            val loader = LatestRequestLoader<String>(this) {
                results += it
                newPublished.complete(Unit)
            }
            loader.load {
                withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                    oldFinished.complete(Unit)
                    if (failOldRequest) throw IOException("Obsolete failure")
                    "old"
                }
            }
            oldStarted.await()
            loader.load { "new" }
            newPublished.await()
            releaseOld.complete(Unit)
            oldFinished.await()
            yield()
            assertEquals(listOf("new"), results.map { it.getOrThrow() })
        }
    }

    @Test
    fun latestFailureIsPublishedAndNextRequestCanRecover() = runBlocking {
        val results = mutableListOf<Result<String>>()
        val failed = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        val loader = LatestRequestLoader<String>(this) {
            results += it
            if (it.isFailure) failed.complete(Unit) else recovered.complete(Unit)
        }
        val error = IOException("Current failure")
        loader.load { throw error }
        failed.await()
        loader.load { "recovered" }
        recovered.await()
        assertEquals(error, results.first().exceptionOrNull())
        assertEquals("recovered", results.last().getOrThrow())
    }
}
