package com.example.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Throttles/debounces emissions of this Flow so that values are emitted at most once
 * per [intervalMs] (e.g. 500ms).
 *
 * Saniyede yüzlerce kez gelen veri akışını arayüze 500 milisaniye aralıklarla sınırlar.
 * Ara değerler conflate edilir (en son gelen değer saklanır) ve belirlenen aralık
 * dolduğunda en güncel veri arayüze iletilir.
 */
fun <T : Any> Flow<T>.throttleLatest(intervalMs: Long = 500L): Flow<T> = flow {
    coroutineScope {
        val channel = Channel<T>(Channel.CONFLATED)
        val producer = launch {
            try {
                collect { value ->
                    channel.send(value)
                }
            } finally {
                channel.close()
            }
        }

        var lastEmissionTime = 0L

        while (true) {
            val result = channel.receiveCatching()
            if (result.isClosed) break
            var latestItem: T = result.getOrNull() ?: break

            val now = System.currentTimeMillis()
            val elapsed = now - lastEmissionTime
            if (lastEmissionTime != 0L && elapsed < intervalMs) {
                delay(intervalMs - elapsed)
                // Drain any newer items that arrived in the conflated channel during the delay
                var poll = channel.tryReceive()
                while (poll.isSuccess) {
                    val polledItem = poll.getOrNull()
                    if (polledItem != null) {
                        latestItem = polledItem
                    }
                    poll = channel.tryReceive()
                }
            }

            emit(latestItem)
            lastEmissionTime = System.currentTimeMillis()
        }

        producer.join()
    }
}
