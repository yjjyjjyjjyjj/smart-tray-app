package kr.ac.gachon.smarttray.bluetooth

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RfcommSessionTest {
    private class Socket(blockConnect: Boolean = false, val failWrite: Boolean = false) : SerialSocket {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(if (blockConnect) 1 else 0)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val reads = LinkedBlockingQueue<Any>()
        val outputBytes = ByteArrayOutputStream()
        var worker = ""
        override fun connect() { worker=Thread.currentThread().name; started.countDown(); gate.await() }
        override val input = object : InputStream() {
            override fun read() = error("Use bulk read")
            override fun read(buffer: ByteArray): Int {
                return when (val part = reads.take()) {
                    is ByteArray -> { part.copyInto(buffer); part.size }
                    is IOException -> throw part
                    else -> -1
                }
            }
        }
        override val output = object : OutputStream() {
            override fun write(b: Int) { if(failWrite) throw IOException("write failed"); outputBytes.write(b) }
        }
        override fun close() { closeCount.incrementAndGet(); gate.countDown(); reads.offer("EOF"); closed.countDown() }
    }
    private class Events(private val expectedChunks: Int=0) : RfcommSession.Listener {
        val online=CountDownLatch(1); val failed=CountDownLatch(1); val received=CountDownLatch(expectedChunks)
        val connections=AtomicInteger(); val failures=CopyOnWriteArrayList<Exception>(); val chunks=CopyOnWriteArrayList<List<Byte>>()
        override fun connected() { connections.incrementAndGet(); online.countDown() }
        override fun bytes(chunk: ByteArray) { chunks.add(chunk.toList()); received.countDown() }
        override fun failed(error: Exception) { failures.add(error); failed.countDown() }
    }
    private fun CountDownLatch.done() = assertTrue("Asynchronous operation timed out", await(3, TimeUnit.SECONDS))
    @Test fun onlySuccessfulSocketConnectSignalsConnectedAndRunsOffCallerThread() {
        val socket=Socket(true); val events=Events(); val calling=Thread.currentThread().name
        RfcommSession({socket}, Executor{it.run()}).use { session ->
            session.connect("selected-address",events); socket.started.done()
            assertEquals(0,events.connections.get()); assertNotEquals(calling,socket.worker)
            socket.gate.countDown(); events.online.done(); assertEquals(1,events.connections.get())
        }
        socket.closed.done(); assertEquals(1,socket.closeCount.get())
    }
    @Test fun timeoutClosesBlockedConnectAndSuppressesLateSuccess() {
        val socket=Socket(true); val events=Events()
        RfcommSession({socket},Executor{it.run()},80).use { session ->
            session.connect("selected",events); events.failed.done(); socket.closed.done()
            assertTrue(events.failures.single() is TimeoutException); assertEquals(0,events.connections.get())
        }
    }
    @Test fun explicitCancelClosesSocketWithoutFailureOrRetryNotification() {
        val socket=Socket(true); val events=Events()
        RfcommSession({socket},Executor{it.run()}).use { session ->
            session.connect("selected",events); socket.started.done(); session.stop(); socket.closed.done()
            assertEquals(0,events.connections.get()); assertTrue(events.failures.isEmpty())
            assertTrue(session.write(byteArrayOf(1)).isCompletedExceptionally)
        }
    }
    @Test fun replacingSessionUsesOnlySelectedAddressAndDropsQueuedOldCallbacks() {
        val first=Socket(); val second=Socket(); val sockets=listOf(first,second).iterator()
        val addresses=CopyOnWriteArrayList<String>(); val callbacks=LinkedBlockingQueue<Runnable>()
        val old=Events(); val latest=Events()
        RfcommSession({ addresses.add(it); sockets.next() },Executor{callbacks.add(it)}).use { session ->
            session.connect("first",old); val oldCallback=callbacks.poll(3,TimeUnit.SECONDS)!!
            session.connect("second",latest); first.closed.done()
            val newCallback=callbacks.poll(3,TimeUnit.SECONDS)!!
            oldCallback.run(); newCallback.run()
            assertEquals(0,old.connections.get()); assertEquals(1,latest.connections.get())
            assertEquals(listOf("first","second"),addresses)
        }
    }
    @Test fun streamChunksRemainOrderedAndAreNotAssumedToBeMessages() {
        val socket=Socket(); val events=Events(3)
        RfcommSession({socket},Executor{it.run()}).use { session ->
            session.connect("selected",events); events.online.done()
            socket.reads.add(byteArrayOf(2)); socket.reads.add(byteArrayOf(7)); socket.reads.add(byteArrayOf(8,1,9))
            events.received.done()
            // A TEST-ONLY length framing fixture, NOT the Smart Tray/Arduino protocol.
            val buffer=ArrayList<Byte>(); val frames=ArrayList<List<Byte>>()
            events.chunks.forEach { chunk -> buffer.addAll(chunk); while(buffer.isNotEmpty() && buffer.size>buffer[0].toInt()) {
                val length=buffer.removeAt(0).toInt(); frames.add((0 until length).map{buffer.removeAt(0)})
            } }
            assertEquals(listOf(listOf<Byte>(7,8),listOf<Byte>(9)),frames)
            assertTrue(ArduinoJsonProtocol().feed(byteArrayOf(2,7,8,1,9)).isEmpty())
        }
    }
    @Test fun eofClosesSocketAndSignalsOneFailure() {
        val socket=Socket(); val events=Events()
        RfcommSession({socket},Executor{it.run()}).use { session ->
            session.connect("selected",events); events.online.done(); socket.reads.add("EOF")
            events.failed.done(); socket.closed.done()
            assertTrue(events.failures.single() is EOFException); assertEquals(1,socket.closeCount.get())
        }
    }
    @Test fun readIoExceptionClosesAndNeverInventsSensorData() {
        val socket=Socket(); val events=Events()
        RfcommSession({socket},Executor{it.run()}).use { session ->
            session.connect("selected",events); events.online.done(); socket.reads.add(IOException("distance loss"))
            events.failed.done(); socket.closed.done(); assertTrue(events.chunks.isEmpty())
        }
    }
    @Test fun writeCopiesPayloadAndSerializesOnWorker() {
        val socket=Socket(); val events=Events()
        RfcommSession({socket},Executor{it.run()}).use { session ->
            session.connect("selected",events); events.online.done()
            val payload=byteArrayOf(1,2); val a=session.write(payload); payload[0]=99
            val b=session.write(byteArrayOf(3)); a.get(3,TimeUnit.SECONDS); b.get(3,TimeUnit.SECONDS)
            assertArrayEquals(byteArrayOf(1,2,3),socket.outputBytes.toByteArray())
        }
    }
    @Test fun writeFailureClosesSocketAndRejectsResult() {
        val socket=Socket(failWrite=true); val events=Events()
        RfcommSession({socket},Executor{it.run()}).use { session ->
            session.connect("selected",events); events.online.done()
            try { session.write(byteArrayOf(1)).get(3,TimeUnit.SECONDS); fail("Write must fail") } catch(_:ExecutionException) { }
            events.failed.done(); socket.closed.done()
        }
    }
    @Test fun retriesContinueAtThirtySecondsAfterInitialBackoff() {
        val policy=ReconnectPolicy()
        assertEquals(listOf(2000L,4000L,8000L,16000L,30000L),(1..5).map{policy.nextDelay()})
        repeat(100) { assertEquals(30000L,policy.nextDelay()) }
        assertEquals(105,policy.attempts)
        policy.reset(); assertEquals(0,policy.attempts)
        assertEquals(2000L,policy.nextDelay())
    }
    @Test fun serviceRestorationPreservesBackoffAndLongAbsencesDoNotOverflow() {
        assertEquals(16000L,ReconnectPolicy(3).nextDelay())
        assertEquals(30000L,ReconnectPolicy(4).nextDelay())
        assertEquals(30000L,ReconnectPolicy(5).nextDelay())
        assertEquals(30000L,ReconnectPolicy(1000).nextDelay())
        val saturated=ReconnectPolicy(Int.MAX_VALUE)
        repeat(2) { assertEquals(30000L,saturated.nextDelay()) }
        assertEquals(Int.MAX_VALUE,saturated.attempts)
        assertEquals(2000L,ReconnectPolicy(-1).nextDelay())
    }
}
