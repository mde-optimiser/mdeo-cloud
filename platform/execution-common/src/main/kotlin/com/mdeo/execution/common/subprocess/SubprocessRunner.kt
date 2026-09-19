package com.mdeo.execution.common.subprocess

import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a task in a child JVM process with cancellation monitoring.
 *
 * The subprocess communicates with the parent process using length-prefixed CBOR messages
 * ([SubprocessMessage]) over stdin/stdout. A background thread polls [cancellationCheck]
 * periodically and forcibly destroys the subprocess if cancellation is detected.
 * In-subprocess timeouts are managed by [ChildProcessWatchdog] inside the child JVM.
 *
 * Subprocess stdout text (sent via [SubprocessMessage.Stdout]) is forwarded to the parent's
 * System.out. Channel messages ([SubprocessMessage.Channel]) can be used for general-purpose
 * bidirectional communication.
 *
 * A JVM shutdown hook ensures the subprocess is forcibly killed if the parent JVM exits
 * unexpectedly (e.g. via Ctrl+C or SIGTERM).
 *
 * @param mainClass The fully qualified class name of the subprocess entry point.
 *        Must have a `main(args: Array<String>)` method.
 * @param jvmArgs Additional JVM arguments for the subprocess.
 * @param cancellationCheck Called with the current [executionId] to determine whether the
 *        execution has been externally cancelled.  Receives the execution identifier so that
 *        the same function can be reused across pool cycles without reassignment.
 * @param cancellationCheckIntervalMs Interval between cancellation checks in milliseconds.
 * @param onChannelMessage Callback for channel messages received from the subprocess.
 *        The callback receives the message payload and a function to send a response back.
 * @param classPath The classpath for the subprocess. Defaults to the parent's classpath.
 */
class SubprocessRunner(
    private val mainClass: String,
    private val jvmArgs: List<String> = emptyList(),
    cancellationCheck: (String) -> Boolean = { _ -> false },
    private val cancellationCheckIntervalMs: Long = 5000L,
    onChannelMessage: ((ByteArray, (ByteArray) -> Unit) -> Unit)? = null,
    private val classPath: String = System.getProperty("java.class.path")
) {
    private val logger = LoggerFactory.getLogger(SubprocessRunner::class.java)

    /**
     * Identifier of the execution this subprocess is currently serving.
     *
     * Passed to [cancellationCheck] on each poll.  When `null` the cancellation thread
     * skips polling entirely — this is the normal state for idle / pooled subprocesses.
     * Callers must set this before (or immediately after) [start] to enable cancellation.
     */
    @Volatile
    var executionId: String? = null

    /**
     * Called with the current [executionId] to determine whether the execution has been
     * externally cancelled.  The function is intentionally parameterised so that it can
     * be set once per service and reused across subprocess-pool cycles — only [executionId]
     * needs to be updated when the subprocess is assigned to a new execution.
     */
    @Volatile
    var cancellationCheck: (String) -> Boolean = cancellationCheck

    /**
     * Callback invoked when a [SubprocessMessage.Channel] message is received from
     * the subprocess. Can be reassigned to support process reuse across different
     * execution contexts (e.g. subprocess pools).
     *
     * The callback receives the message payload and a function to send a response back.
     */
    @Volatile
    var onChannelMessage: ((ByteArray, (ByteArray) -> Unit) -> Unit)? = onChannelMessage

    /**
     * Called once when the subprocess exits (either cleanly, by timeout, or by crash).
     * Can be used to cancel any pending operations that were waiting on subprocess output.
     */
    @Volatile
    var onProcessExited: (() -> Unit)? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var shutdownHook: Thread? = null

    private val destroyed = AtomicBoolean(false)
    private val completionLatch = CountDownLatch(1)
    private val resultHolder = LinkedBlockingQueue<SubprocessResult>(1)

    @Volatile
    private var processOutput: MessageWriter? = null

    private val commandIdCounter = AtomicInteger(0)
    private val channelIdCounter = AtomicInteger(0)

    /**
     * Whether the subprocess is still running.
     */
    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Sends a command to the subprocess and waits for the result.
     *
     * A unique numeric [id][SubprocessMessage.Command.id] is automatically assigned to the
     * command. The subprocess is expected to respond with [SubprocessMessage.Result] or
     * [SubprocessMessage.Error] carrying the same id.
     *
     * @param payload The command data to send.
     * @return The result of the subprocess execution.
     */
    fun sendCommand(payload: ByteArray): SubprocessResult {
        val id = commandIdCounter.incrementAndGet()
        val out = processOutput ?: return SubprocessResult.Failed("Subprocess not started")
        try {
            out.write(SubprocessMessage.Command(id, payload))
        } catch (e: IOException) {
            return SubprocessResult.Failed("Failed to send command: ${e.message}")
        }
        return awaitResult()
    }

    /**
     * Sends a channel message to the subprocess.
     *
     * A failure is raised rather than logged: callers that expect an answer register for it
     * before sending, so a send that quietly did nothing leaves them waiting on a message the
     * subprocess never received.
     *
     * @param payload The message data to send.
     * @throws IOException if the message could not be written to the subprocess.
     */
    fun sendChannelMessage(payload: ByteArray) {
        val id = channelIdCounter.incrementAndGet()
        val out = processOutput ?: throw IOException("Subprocess not started")
        out.write(SubprocessMessage.Channel(id, payload))
    }

    /**
     * Starts the subprocess and waits for it to signal readiness.
     *
     * Registers a JVM shutdown hook that forcibly kills the subprocess if the parent
     * JVM exits before [stop] or [destroy] is called (e.g. via Ctrl+C or SIGTERM).
     *
     * @param startupTimeoutMs Maximum time to wait for the subprocess to become ready.
     * @return `true` if the subprocess started successfully, `false` on failure or timeout.
     */
    fun start(startupTimeoutMs: Long = 30000L): Boolean {
        val javaPath = ProcessHandle.current().info().command().orElse("java")
        val command = mutableListOf(javaPath).apply {
            addAll(jvmArgs)
            add("-cp")
            add(classPath)
            add(mainClass)
        }

        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(false)

        val proc = pb.start()
        this.process = proc

        val hook = Thread {
            if (!destroyed.get()) {
                logger.warn("JVM shutdown hook triggered, forcibly destroying subprocess")
                proc.destroyForcibly()
            }
        }.also {
            it.name = "subprocess-shutdown-hook"
            Runtime.getRuntime().addShutdownHook(it)
        }
        this.shutdownHook = hook

        this.processOutput = MessageWriter(DataOutputStream(BufferedOutputStream(proc.outputStream)))

        Thread {
            proc.errorStream.bufferedReader().forEachLine { line ->
                System.err.println("[subprocess] $line")
            }
        }.apply {
            isDaemon = true
            name = "subprocess-stderr"
            start()
        }

        Thread {
            while (!destroyed.get()) {
                val id = executionId
                if (id != null) {
                    val cancelled = cancellationCheck(id)
                    if (cancelled) {
                        if (destroyed.compareAndSet(false, true)) {
                            logger.info("Execution {} cancelled, destroying subprocess", id)
                            proc.destroyForcibly()
                            resultHolder.offer(SubprocessResult.Cancelled)
                            completionLatch.countDown()
                        }
                        break
                    }
                }
                try {
                    Thread.sleep(cancellationCheckIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "subprocess-cancellation"
            start()
        }

        val readyLatch = CountDownLatch(1)
        Thread {
            readSubprocessOutput(DataInputStream(BufferedInputStream(proc.inputStream)), readyLatch)
        }.apply {
            isDaemon = true
            name = "subprocess-reader"
            start()
        }

        val ready = readyLatch.await(startupTimeoutMs, TimeUnit.MILLISECONDS)
        if (!ready) {
            destroy()
            return false
        }
        return true
    }

    /**
     * Sends a quit message and waits for the subprocess to exit.
     *
     * @param timeoutMs Maximum time to wait for clean shutdown.
     * @return `true` if the subprocess exited cleanly.
     */
    fun stop(timeoutMs: Long = 5000L): Boolean {
        val out = processOutput
        if (out != null) {
            try {
                out.write(SubprocessMessage.Quit)
            } catch (_: IOException) {
                // Process may already be dead
            }
        }
        val exited = process?.waitFor(timeoutMs, TimeUnit.MILLISECONDS) ?: true
        if (!exited) {
            destroy()
        }
        removeShutdownHook()
        return exited
    }

    /**
     * Forcibly destroys the subprocess.
     */
    fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            process?.destroyForcibly()
            removeShutdownHook()
        }
    }

    private fun removeShutdownHook() {
        try {
            shutdownHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
        } catch (_: IllegalStateException) {
            // JVM is already shutting down — hook removal is not possible
        }
    }

    private fun awaitResult(): SubprocessResult {
        val result = resultHolder.poll(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        return result ?: SubprocessResult.Failed("No result received")
    }

    private fun readSubprocessOutput(input: DataInputStream, readyLatch: CountDownLatch) {
        try {
            while (!destroyed.get()) {
                val message = SubprocessMessage.read(input) ?: break

                when (message) {
                    is SubprocessMessage.Ready -> {
                        readyLatch.countDown()
                    }
                    is SubprocessMessage.Result -> {
                        resultHolder.offer(SubprocessResult.Success(message.payload))
                        completionLatch.countDown()
                    }
                    is SubprocessMessage.Error -> {
                        resultHolder.offer(SubprocessResult.Failed(message.message))
                        completionLatch.countDown()
                    }
                    is SubprocessMessage.Stdout -> {
                        System.out.println(message.text)
                    }
                    is SubprocessMessage.Channel -> {
                        onChannelMessage?.invoke(message.payload) { response ->
                            sendChannelMessage(response)
                        }
                    }
                    is SubprocessMessage.Timeout -> {
                        resultHolder.offer(SubprocessResult.Timeout(message.timeoutId))
                        completionLatch.countDown()
                        if (destroyed.compareAndSet(false, true)) {
                            process?.destroyForcibly()
                            removeShutdownHook()
                        }
                    }
                    is SubprocessMessage.Command, SubprocessMessage.Quit -> {
                        logger.warn("Unexpected message type from subprocess: {}", message::class.simpleName)
                    }
                }
            }
        } catch (_: EOFException) {
            // Subprocess closed
        } catch (e: IOException) {
            if (!destroyed.get()) {
                logger.warn("Error reading subprocess output: {}", e.message)
            }
        } finally {
            if (destroyed.compareAndSet(false, true)) {
                val exitCode = try { process?.waitFor() } catch (_: Exception) { null }
                resultHolder.offer(SubprocessResult.Failed("Subprocess exited unexpectedly", exitCode))
                completionLatch.countDown()
            }
            onProcessExited?.invoke()
        }
    }
}
