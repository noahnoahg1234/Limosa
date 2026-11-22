package com.limosa.core.nbd

import com.limosa.core.storage.ChunkStore
import com.limosa.core.storage.ReadOnlyException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A Network Block Device server (fixed newstyle protocol) bound to 127.0.0.1 only.
 * It exports every disk of a [ChunkStore], so QEMU can attach one with
 * `-drive file=nbd://127.0.0.1:<port>/<disk>,format=raw`.
 *
 * Supported: OPT_EXPORT_NAME, OPT_GO, OPT_INFO, OPT_LIST, OPT_ABORT and the
 * READ / WRITE / FLUSH / TRIM / WRITE_ZEROES / DISC commands (simple replies).
 */
class NbdServer(
    private val store: ChunkStore,
    port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
) : Closeable {

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(com.limosa.core.LOOPBACK_V4, port))
    }
    val port: Int get() = server.localPort
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "nbd-conn").apply { isDaemon = true }
    }
    private val clients = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var running = true

    /** Number of open client connections (i.e. disks in use by QEMU). */
    val connections: Int get() = clients.size

    fun start(): NbdServer {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (e: IOException) { if (running) log("nbd accept: $e"); break }
                s.tcpNoDelay = true
                clients.add(s)
                pool.execute {
                    try {
                        Session(s).run()
                    } catch (e: EOFException) {
                        // client went away
                    } catch (e: SocketException) {
                        // closed
                    } catch (e: Exception) {
                        log("nbd session error: $e")
                    } finally {
                        clients.remove(s)
                        runCatching { s.close() }
                    }
                }
            }
        }, "nbd-accept").apply { isDaemon = true }.start()
        return this
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        synchronized(clients) { clients.forEach { runCatching { it.close() } } }
        pool.shutdownNow()
        runCatching { store.flush() }
    }

    private inner class Session(private val sock: Socket) {
        private val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024))
        private val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 256 * 1024))
        private var noZeroes = false

        fun run() {
            // --- handshake
            output.writeLong(NBDMAGIC)
            output.writeLong(IHAVEOPT)
            output.writeShort(FLAG_FIXED_NEWSTYLE or FLAG_NO_ZEROES)
            output.flush()
            val clientFlags = input.readInt()
            if (clientFlags and CLIENT_FIXED_NEWSTYLE == 0) throw IOException("Client lacks fixed newstyle")
            noZeroes = clientFlags and CLIENT_NO_ZEROES != 0

            val disk = negotiate() ?: return
            log("nbd: client attached to '${disk.name}'")
            transmission(disk)
        }

        /** Option haggling. Returns the export to serve, or null if the client aborted. */
        private fun negotiate(): ChunkStore.VirtualDisk? {
            while (true) {
                if (input.readLong() != IHAVEOPT) throw IOException("Bad option magic")
                val opt = input.readInt()
                val len = input.readInt()
                if (len < 0 || len > 1 shl 20) throw IOException("Option too large")
                val data = ByteArray(len).also { input.readFully(it) }
                when (opt) {
                    OPT_EXPORT_NAME -> {
                        val d = store.disk(String(data, Charsets.UTF_8)) ?: throw IOException("Unknown export")
                        output.writeLong(d.size)
                        output.writeShort(transmissionFlags(d))
                        if (!noZeroes) output.write(ByteArray(124))
                        output.flush()
                        return d
                    }
                    OPT_ABORT -> {
                        reply(opt, REP_ACK)
                        return null
                    }
                    OPT_LIST -> {
                        for (d in store.listDisks()) {
                            val n = d.name.toByteArray(Charsets.UTF_8)
                            reply(opt, REP_SERVER, ByteArray(4 + n.size).also {
                                java.nio.ByteBuffer.wrap(it).putInt(n.size).put(n)
                            })
                        }
                        reply(opt, REP_ACK)
                    }
                    OPT_INFO, OPT_GO -> {
                        val b = java.nio.ByteBuffer.wrap(data)
                        if (data.size < 6) { reply(opt, REP_ERR_INVALID); continue }
                        val nameLen = b.int
                        if (nameLen < 0 || nameLen > data.size - 6) { reply(opt, REP_ERR_INVALID); continue }
                        val name = String(data, 4, nameLen, Charsets.UTF_8)
                        val d = store.disk(name)
                        if (d == null) { reply(opt, REP_ERR_UNKNOWN, "No such disk: $name".toByteArray()); continue }
                        reply(opt, REP_INFO, java.nio.ByteBuffer.allocate(12)
                            .putShort(INFO_EXPORT.toShort()).putLong(d.size).putShort(transmissionFlags(d).toShort()).array())
                        reply(opt, REP_INFO, java.nio.ByteBuffer.allocate(14)
                            .putShort(INFO_BLOCK_SIZE.toShort()).putInt(1).putInt(4096).putInt(MAX_REQUEST).array())
                        reply(opt, REP_ACK)
                        if (opt == OPT_GO) return d
                    }
                    else -> reply(opt, REP_ERR_UNSUP)
                }
            }
        }

        private fun transmissionFlags(d: ChunkStore.VirtualDisk): Int {
            var f = TF_HAS_FLAGS or TF_SEND_FLUSH or TF_SEND_FUA
            f = if (d.readOnly) f or TF_READ_ONLY else f or TF_SEND_TRIM or TF_SEND_WRITE_ZEROES
            return f
        }

        private fun reply(opt: Int, type: Int, data: ByteArray = ByteArray(0)) {
            output.writeLong(REPLY_MAGIC)
            output.writeInt(opt)
            output.writeInt(type)
            output.writeInt(data.size)
            output.write(data)
            output.flush()
        }

        private fun transmission(disk: ChunkStore.VirtualDisk) {
            var buf = ByteArray(1024 * 1024)
            while (true) {
                if (input.readInt() != REQUEST_MAGIC) throw IOException("Bad request magic")
                val flags = input.readUnsignedShort()
                val type = input.readUnsignedShort()
                val handle = input.readLong()
                val offset = input.readLong()
                val length = input.readInt().toLong() and 0xffffffffL

                if (type == CMD_WRITE) {
                    if (length > MAX_REQUEST) throw IOException("Write too large: $length")
                    if (buf.size < length) buf = ByteArray(length.toInt())
                    input.readFully(buf, 0, length.toInt())
                }

                var error = 0
                var readLen = 0
                try {
                    when (type) {
                        CMD_READ -> {
                            if (length > MAX_REQUEST) throw IllegalArgumentException("Read too large")
                            if (buf.size < length) buf = ByteArray(length.toInt())
                            disk.read(offset, buf, 0, length.toInt())
                            readLen = length.toInt()
                        }
                        CMD_WRITE -> {
                            disk.write(offset, buf, 0, length.toInt())
                            if (flags and CMDF_FUA != 0) disk.flush()
                        }
                        CMD_FLUSH -> disk.flush()
                        CMD_TRIM -> disk.trim(offset, length)
                        CMD_WRITE_ZEROES -> {
                            disk.zero(offset, length)
                            if (flags and CMDF_FUA != 0) disk.flush()
                        }
                        CMD_DISC -> {
                            disk.flush()
                            return
                        }
                        else -> error = EINVAL
                    }
                } catch (e: ReadOnlyException) {
                    error = EPERM
                } catch (e: IndexOutOfBoundsException) {
                    error = if (type == CMD_WRITE || type == CMD_WRITE_ZEROES) ENOSPC else EINVAL
                } catch (e: IllegalArgumentException) {
                    error = EINVAL
                } catch (e: IOException) {
                    log("nbd I/O error on ${disk.name}: $e")
                    error = EIO
                }

                output.writeInt(SIMPLE_REPLY_MAGIC)
                output.writeInt(error)
                output.writeLong(handle)
                if (error == 0 && readLen > 0) output.write(buf, 0, readLen)
                output.flush()
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 10809
        const val MAX_REQUEST = 32 * 1024 * 1024

        private const val NBDMAGIC = 0x4e42444d41474943L
        private const val IHAVEOPT = 0x49484156454F5054L
        private const val REPLY_MAGIC = 0x3e889045565a9L
        private const val REQUEST_MAGIC = 0x25609513
        private const val SIMPLE_REPLY_MAGIC = 0x67446698

        private const val FLAG_FIXED_NEWSTYLE = 1
        private const val FLAG_NO_ZEROES = 2
        private const val CLIENT_FIXED_NEWSTYLE = 1
        private const val CLIENT_NO_ZEROES = 2

        private const val OPT_EXPORT_NAME = 1
        private const val OPT_ABORT = 2
        private const val OPT_LIST = 3
        private const val OPT_INFO = 6
        private const val OPT_GO = 7

        private const val REP_ACK = 1
        private const val REP_SERVER = 2
        private const val REP_INFO = 3
        private const val REP_ERR_UNSUP = (1 shl 31) or 1
        private const val REP_ERR_INVALID = (1 shl 31) or 3
        private const val REP_ERR_UNKNOWN = (1 shl 31) or 6

        private const val INFO_EXPORT = 0
        private const val INFO_BLOCK_SIZE = 3

        private const val TF_HAS_FLAGS = 1
        private const val TF_READ_ONLY = 2
        private const val TF_SEND_FLUSH = 4
        private const val TF_SEND_FUA = 8
        private const val TF_SEND_TRIM = 32
        private const val TF_SEND_WRITE_ZEROES = 64

        private const val CMD_READ = 0
        private const val CMD_WRITE = 1
        private const val CMD_DISC = 2
        private const val CMD_FLUSH = 3
        private const val CMD_TRIM = 4
        private const val CMD_WRITE_ZEROES = 6
        private const val CMDF_FUA = 1

        private const val EPERM = 1
        private const val EIO = 5
        private const val EINVAL = 22
        private const val ENOSPC = 28
    }
}
