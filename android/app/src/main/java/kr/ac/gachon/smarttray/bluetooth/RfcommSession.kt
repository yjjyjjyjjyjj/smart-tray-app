package kr.ac.gachon.smarttray.bluetooth

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

object SppProfile {
    // Bluetooth SIG Serial Port service class, NOT the Arduino sensor packet specification.
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
}
interface SerialSocket : Closeable {
    fun connect()
    val input: InputStream
    val output: OutputStream
}

/** One blocking connector/reader, one ordered writer and a separate cancellation/close worker.
 * The Android Service owns this session. No Activity, WebView or JavaScript dependency. */
class RfcommSession(
    private val factory: (String) -> SerialSocket,
    private val callbacks: Executor,
    private val connectTimeoutMs: Long = 20_000,
    private val io: ExecutorService = Executors.newSingleThreadExecutor(),
    private val writer: ExecutorService = Executors.newSingleThreadExecutor(),
    private val closer: ExecutorService = Executors.newSingleThreadExecutor(),
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) : Closeable {
    interface Listener {
        fun connected()
        /** Runs on the I/O worker, before its next read. Never treat one chunk as one frame. */
        fun bytes(chunk: ByteArray)
        fun failed(error: Exception)
    }
    private class Attempt(val id: Long, val listener: Listener) {
        var socket: SerialSocket? = null
        var connected = false
        var timeout: ScheduledFuture<*>? = null
        val closing = AtomicBoolean()
    }
    private val lock = Any()
    private var generation = 0L
    private var current: Attempt? = null
    private var disposed = false
    private val writes = mutableSetOf<CompletableFuture<Unit>>()
    private fun deliver(a: Attempt, action: () -> Unit) {
        callbacks.execute { synchronized(lock) { if (!disposed && a.id == generation) action() } }
    }
    fun connect(address: String, listener: Listener) = synchronized(lock) {
        check(!disposed) { "Session is closed" }
        stopLocked()
        val a = Attempt(generation, listener); current = a
        a.timeout = timer.schedule({ fail(a, TimeoutException("RFCOMM connection timed out")) }, connectTimeoutMs, TimeUnit.MILLISECONDS)
        io.execute {
            try {
                val socket = factory(address)
                synchronized(lock) {
                    if (current !== a) { closer.execute { runCatching { socket.close() } }; return@execute }
                    a.socket = socket
                }
                socket.connect() // Blocks here, never on Android's main looper.
                synchronized(lock) {
                    if (current !== a) return@execute
                    a.connected = true; a.timeout?.cancel(false)
                    deliver(a) { listener.connected() }
                }
                val input = socket.input
                val buffer = ByteArray(2048)
                while (synchronized(lock) { current === a }) {
                    val size = input.read(buffer)
                    if (size < 0) throw EOFException("RFCOMM stream ended")
                    if (size > 0 && synchronized(lock) { current === a }) listener.bytes(buffer.copyOf(size))
                }
            } catch (error: Exception) { fail(a, error) }
            finally { closeSocket(a) }
        }
    }
    /** Internal transport only. No web raw-write endpoint or invented hardware commands. */
    fun write(bytes: ByteArray): CompletableFuture<Unit> = synchronized(lock) {
        val result = CompletableFuture<Unit>()
        val a = current
        if (a == null || !a.connected || disposed || writes.size >= 16 || bytes.isEmpty() || bytes.size > 65536) {
            result.completeExceptionally(IOException("Not connected or write queue/payload limit exceeded")); return result
        }
        val copy = bytes.copyOf(); writes.add(result)
        writer.execute {
            try {
                val socket = synchronized(lock) { if (current === a) a.socket else null }
                    ?: throw CancellationException("Connection changed")
                socket.output.write(copy); socket.output.flush()
                synchronized(lock) {
                    writes.remove(result)
                    if (current === a) result.complete(Unit) else result.completeExceptionally(CancellationException("Connection changed"))
                }
            } catch (error: Exception) {
                synchronized(lock) { writes.remove(result); result.completeExceptionally(error) }
                fail(a, error)
            }
        }
        result
    }
    private fun fail(a: Attempt, error: Exception) = synchronized(lock) {
        if (current !== a) return
        current = null; a.timeout?.cancel(false); cancelWrites(error); closeSocket(a)
        deliver(a) { a.listener.failed(error) }
    }
    private fun closeSocket(a: Attempt) {
        val socket = synchronized(lock) { a.socket } ?: return
        if (a.closing.compareAndSet(false, true)) closer.execute { runCatching { socket.close() } }
    }
    private fun cancelWrites(error: Exception) { val pending = writes.toList(); writes.clear(); pending.forEach { it.completeExceptionally(error) } }
    private fun stopLocked() {
        generation++
        val old = current; current = null
        old?.timeout?.cancel(false); old?.let(::closeSocket)
        cancelWrites(CancellationException("Connection cancelled"))
    }
    fun stop() = synchronized(lock) { stopLocked() }
    override fun close(): Unit = synchronized(lock) {
        if (disposed) return
        stopLocked(); disposed = true
        io.shutdownNow(); writer.shutdownNow(); timer.shutdownNow()
        // Keep the close worker alive until I/O termination, so a late socket factory result
        // can still be closed after cancellation.
        Thread { while (!io.awaitTermination(1, TimeUnit.DAYS)) { /* Wait for late factory/close cleanup. */ }; closer.shutdown() }.apply { isDaemon = true; start() }
        Unit
    }
}

/** Retry after each failed attempt: 2, 4, 8, 16, then 30 seconds until connected or stopped. */
class ReconnectPolicy(attempts: Int = 0) {
    var attempts = attempts.coerceAtLeast(0)
        private set
    fun nextDelay(): Long {
        if (attempts < Int.MAX_VALUE) attempts++
        // Bound the exponent, not the number of retries, including after service restoration.
        return minOf(30_000L, 1000L shl attempts.coerceAtMost(5))
    }
    fun reset() { attempts = 0 }
}
