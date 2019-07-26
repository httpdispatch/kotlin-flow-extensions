package hu.akarnokd.kotlin.flow

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.atomic.AtomicReference

/**
 * Caches one item and replays it to fresh collectors.
 */
@FlowPreview
class BehaviorSubject<T> : AbstractFlow<T>, SubjectAPI<T> {

    private companion object {
        private val EMPTY = arrayOf<InnerCollector<Any>>()
        private val TERMINATED = arrayOf<InnerCollector<Any>>()
        private val NONE = Object()
        private val DONE = Node<Any>(NONE);
    }

    @Suppress("UNCHECKED_CAST")
    private val collectors = AtomicReference(EMPTY as Array<InnerCollector<T>>)

    @Volatile
    private var current: Node<T>

    private var error: Throwable? = null

    /**
     * Constructs an empty BehaviorSubject.
     */
    @Suppress("UNCHECKED_CAST")
    constructor() {
        current = Node(NONE as T)
    }

    /**
     * Constructs a BehaviorSubject with an initial value to emit
     * to collectors.
     */
    constructor(initialValue: T) {
        current = Node(initialValue)
    }

    /**
     * Returns true if this subject has collectors waiting for data.
     */
    override fun hasCollectors(): Boolean = collectors.get().isNotEmpty()

    /**
     * Returns the number of collectors waiting for data.
     */
    override fun collectorCount(): Int = collectors.get().size

    /**
     * Emit a value to all current collectors when they are ready.
     */
    override suspend fun emit(value: T) {
        if (current != DONE) {
            val next = Node<T>(value)
            current.set(next)
            current = next

            for (collector in collectors.get()) {
                collector.consumeReady.await()

                collector.resume()
            }
        }
    }

    /**
     * Signal an exception to all current and future collectors when
     * they are ready.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun emitError(ex: Throwable) {
        if (current != DONE) {
            error = ex
            current.set(DONE as Node<T>)
            current = DONE

            for (collector in collectors.getAndSet(TERMINATED as Array<InnerCollector<T>>)) {
                collector.consumeReady.await()

                collector.resume()
            }
        }
    }

    /**
     * Signal current and future collectors that no further
     * values will be coming.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun complete() {
        if (current != DONE) {
            current.set(DONE as Node<T>)
            current = DONE

            for (collector in collectors.getAndSet(TERMINATED as Array<InnerCollector<T>>)) {
                collector.consumeReady.await()

                collector.resume()
            }
        }
    }

    /**
     * Accepts a [collector] and emits the latest (if available) value
     * and any subsequent value received by this BehaviorSubject until
     * the BehaviorSubject gets terminated.
     */
    override suspend fun collectSafely(collector: FlowCollector<T>) {
        val inner = InnerCollector<T>()
        if (add(inner)) {
            var curr = current

            if (curr.value != NONE) {
                try {
                    collector.emit(curr.value)
                } catch (exc: Throwable) {
                    remove(inner)

                    inner.consumeReady.resume()

                    throw exc
                }
            }

            while (true) {
                inner.consumeReady.resume()

                inner.await()

                val next = curr.get()

                if (next == DONE) {
                    val ex = error
                    if (ex != null) {
                        throw ex
                    }
                    return
                }

                try {
                    collector.emit(next.value)
                } catch (exc: Throwable) {
                    remove(inner)

                    inner.consumeReady.resume()

                    throw exc
                }
                curr = next
            }
        }
        val ex = error
        if (ex != null) {
            throw ex
        }
    }

    @Suppress("UNCHECKED_CAST", "")
    private fun add(inner: InnerCollector<T>) : Boolean {
        while (true) {

            val a = collectors.get()
            if (a as Any == TERMINATED as Any) {
                return false
            }
            val n = a.size
            val b = a.copyOf(n + 1)
            b[n] = inner
            if (collectors.compareAndSet(a, b as Array<InnerCollector<T>>)) {
                return true
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun remove(inner: InnerCollector<T>) {
        while (true) {
            val a = collectors.get()
            val n = a.size
            if (n == 0) {
                return
            }

            val j = a.indexOf(inner)
            if (j < 0) {
                return
            }

            var b = EMPTY as Array<InnerCollector<T>?>
            if (n != 1) {
                b = Array(n - 1) { null }
                System.arraycopy(a, 0, b, 0, j)
                System.arraycopy(a, j + 1, b, j, n - j - 1)
            }
            if (collectors.compareAndSet(a, b as Array<InnerCollector<T>>)) {
                return
            }
        }
    }

    /**
     * The collector wrapper that hosts the resumables, "this" is for the valueReady
     */
    private class InnerCollector<T> : Resumable() {
        val consumeReady = Resumable()
    }

    private class Node<T>(val value: T) : AtomicReference<Node<T>>() {

    }
}