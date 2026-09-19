package com.mdeo.execution.common.subprocess

import java.io.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class for subprocess entry points.
 *
 * Subclasses implement [handleCommand] to process incoming commands from the parent
 * process. The protocol uses CBOR-encoded [SubprocessMessage] frames over stdin/stdout.
 * Stdout output from the subprocess should use [writeStdout] so it is properly forwarded
 * to the parent via [SubprocessMessage.Stdout].
 *
 * The subprocess signals readiness automatically after [initialize] completes, then
 * enters a command-processing loop until a [SubprocessMessage.Quit] or EOF is received.
 */
abstract class SubprocessMain {
    private lateinit var output: MessageWriter
    private lateinit var input: DataInputStream

    private val channelIdCounter = AtomicInteger(0)
    private var watchdog: ChildProcessWatchdog? = null

    /**
     * Entry point for the subprocess. Call this from a `main()` function.
     *
     * @param args Command-line arguments passed to the subprocess.
     */
    fun run(args: Array<String>) {
        output = MessageWriter(DataOutputStream(BufferedOutputStream(System.out)))
        input = DataInputStream(BufferedInputStream(System.`in`))

        val originalOut = System.out
        val capturedOut = PrintStream(ForwardingOutputStream(output), true)
        System.setOut(capturedOut)

        watchdog = ChildProcessWatchdog { timeoutId ->
            try {
                onWatchdogTimeout(timeoutId)
            } catch (_: Exception) {
                // Ignore errors in the timeout hook — we are about to halt anyway
            }
            try {
                output.write(SubprocessMessage.Timeout(timeoutId))
            } catch (_: Exception) {
                // Pipe may be broken — proceed to halt
            }
            Runtime.getRuntime().halt(1)
        }

        try {
            initialize(args)
            output.write(SubprocessMessage.Ready)

            commandLoop()
        } catch (e: Exception) {
            try {
                output.write(
                    SubprocessMessage.Error(
                        id = 0,
                        message = "Subprocess initialization failed: ${e.message}"
                    )
                )
            } catch (_: IOException) {
                // Can't communicate with parent
            }
        } finally {
            watchdog?.shutdown()
            System.setOut(originalOut)
            cleanup()
        }
    }

    /**
     * Called on the watchdog thread when a registered timeout expires, **before** the
     * subprocess sends [SubprocessMessage.Timeout] to the parent and halts the JVM.
     *
     * Subclasses may override this to perform a graceful-shutdown handshake (e.g.
     * notifying the orchestrator via an out-of-band channel) before the JVM terminates.
     * Any exception thrown from this method is silently swallowed; the halt proceeds
     * unconditionally afterwards.
     *
     * @param timeoutId The identifier of the expired timeout, as supplied to [registerTimeout].
     */
    protected open fun onWatchdogTimeout(timeoutId: Int) {}

    /**
     * Called once before the subprocess signals readiness.
     * Override to perform any setup (e.g. loading classes, compiling scripts).
     *
     * @param args Command-line arguments.
     */
    protected open fun initialize(args: Array<String>) {}

    /**
     * Handles a command received from the parent process.
     * Must return the result payload bytes on success, or throw an exception on failure.
     *
     * @param payload The command payload bytes.
     * @return The result payload bytes.
     */
    protected abstract fun handleCommand(payload: ByteArray): ByteArray

    /**
     * Handles a channel message received from the parent process.
     * Override to implement bidirectional communication.
     *
     * @param payload The channel message payload.
     */
    protected open fun handleChannelMessage(payload: ByteArray) {}

    /**
     * Called when the subprocess is shutting down.
     * Override to release resources.
     */
    protected open fun cleanup() {}

    /**
     * Registers a timeout on the child-process watchdog.
     *
     * If not cancelled before [durationMs] elapses, the watchdog sends a
     * [SubprocessMessage.Timeout] to the parent and halts the JVM.
     *
     * Thread-safe: can be called from any thread (including parallel mutation threads).
     *
     * @param id Numeric identifier for this timeout.
     * @param durationMs Duration in milliseconds.
     */
    protected fun registerTimeout(id: Int, durationMs: Long) {
        watchdog?.registerTimeout(id, durationMs)
    }

    /**
     * Cancels a previously registered timeout on the child-process watchdog.
     *
     * @param id The numeric identifier of the timeout to cancel.
     */
    protected fun cancelTimeout(id: Int) {
        watchdog?.cancelTimeout(id)
    }

    /**
     * Writes a line of text to be forwarded to the parent's stdout.
     *
     * @param text The text to write.
     */
    protected fun writeStdout(text: String) {
        output.write(SubprocessMessage.Stdout(text))
    }

    /**
     * Sends a channel message to the parent process.
     *
     * @param payload The message payload.
     */
    protected fun sendChannelMessage(payload: ByteArray) {
        val id = channelIdCounter.incrementAndGet()
        output.write(SubprocessMessage.Channel(id, payload))
    }

    private fun commandLoop() {
        while (true) {
            val message = SubprocessMessage.read(input) ?: break

            when (message) {
                is SubprocessMessage.Command -> {
                    val commandId = message.id
                    val commandThread = Thread {
                        try {
                            val result = handleCommand(message.payload)
                            output.write(SubprocessMessage.Result(commandId, result))
                        } catch (e: Throwable) {
                            output.write(
                                SubprocessMessage.Error(
                                    id = commandId,
                                    message = e.message ?: "Unknown error"
                                )
                            )
                        }
                    }
                    commandThread.isDaemon = true
                    commandThread.name = "subprocess-command"
                    commandThread.start()
                }
                is SubprocessMessage.Channel -> {
                    handleChannelMessage(message.payload)
                }
                SubprocessMessage.Quit -> {
                    return
                }
                is SubprocessMessage.Result,
                is SubprocessMessage.Error,
                is SubprocessMessage.Stdout,
                is SubprocessMessage.Timeout,
                SubprocessMessage.Ready -> {
                    // Unexpected direction — ignore
                }
            }
        }
    }

    /**
     * An output stream that captures written bytes and sends them as [SubprocessMessage.Stdout]
     * frames to the parent process, line by line.
     */
    private class ForwardingOutputStream(
        private val protocol: MessageWriter
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (b == '\n'.code) {
                flush()
            } else {
                buffer.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var start = off
            for (i in off until off + len) {
                if (b[i] == '\n'.code.toByte()) {
                    buffer.write(b, start, i - start)
                    flush()
                    start = i + 1
                }
            }
            if (start < off + len) {
                buffer.write(b, start, off + len - start)
            }
        }

        override fun flush() {
            if (buffer.size() > 0) {
                val text = buffer.toString(Charsets.UTF_8.name())
                buffer.reset()
                protocol.write(SubprocessMessage.Stdout(text))
            }
        }
    }
}
