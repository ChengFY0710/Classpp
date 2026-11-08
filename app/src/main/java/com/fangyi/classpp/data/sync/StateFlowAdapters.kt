package com.fangyi.classpp.data.sync

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 只读派生 [StateFlow]：`.value` 同步重算，`collect` 订阅上游。
 *
 * 为什么不 `map + stateIn`：那需要一个长期存活的 CoroutineScope（会话是进程级缓存，
 * 生命周期不短于 App，引入 scope 只增加取消语义负担）；本实现的 `.value` 始终与上游
 * 同步，JVM 单测无需等待调度即可断言，行为确定性更高。
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class DerivedStateFlow<T>(
    private val read: () -> T,
    private val upstream: () -> Flow<T>,
) : StateFlow<T> {

    override val value: T get() = read()

    override val replayCache: List<T> get() = listOf(read())

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        upstream().collect(collector)
        // StateFlow 契约要求 collect 永不正常返回；上游均为 StateFlow，不会走到这里
        error("derived StateFlow completed unexpectedly")
    }
}

/** 单源一一映射：值类型随上游变化，读取时即时换算 */
internal fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> = DerivedStateFlow(
    read = { transform(value) },
    upstream = { map(transform) },
)

/** 双源合成：值由两个 StateFlow 的当前值即时计算 */
internal fun <A, B, R> combineState(
    first: StateFlow<A>,
    second: StateFlow<B>,
    transform: (A, B) -> R,
): StateFlow<R> = DerivedStateFlow(
    read = { transform(first.value, second.value) },
    upstream = { combine(first, second, transform) },
)
