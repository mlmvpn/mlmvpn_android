package kittoku.mvc.extension

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield


internal suspend fun <T> Channel<T>.clear() {
    while (true) {
        yield()
        tryReceive().getOrNull() ?: break
    }
}
