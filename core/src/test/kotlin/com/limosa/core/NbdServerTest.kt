package com.limosa.core

import com.limosa.core.nbd.NbdServer
import com.limosa.core.storage.ChunkStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talks to the NBD server with QEMU's own client (qemu-io / qemu-img), if installed. */
class NbdServerTest {
    @TempDir lateinit var dir: File

    private fun tool(name: String) = listOf("/usr/bin/$name", "/usr/local/bin/$name").map(::File).firstOrNull { it.canExecute() }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), "command failed: ${cmd.joinToString(" ")}\n$out")
        return out
    }

    @Test
    fun `qemu reads writes and discards through nbd`() {
        val qemuIo = tool("qemu-io")
        assumeTrue(qemuIo != null, "qemu-io not installed")
        ChunkStore(dir).use { store ->
            store.createDisk("vm1", 8L shl 30)
            NbdServer(store, 0).start().use { nbd ->
                val url = "nbd://127.0.0.1:${nbd.port}/vm1"
                val out = run(
                    qemuIo!!.path, "-f", "raw",
                    "-c", "write -P 0xab 1M 4M",
                    "-c", "read -P 0xab 1M 4M",
                    "-c", "read -P 0 0 1M",
                    "-c", "write -z 2M 1M",
                    "-c", "read -P 0 2M 1M",
                    "-c", "flush",
                    url,
                )
                assertTrue("Pattern verification failed" !in out, out)
                // 0xab-filled chunks dedupe to one chunk.
                assertEquals(1, store.stats().uniqueChunks)
                assertEquals(3L shl 20, store.disk("vm1")!!.allocatedBytes)

                run(qemuIo.path, "-f", "raw", "-c", "discard 0 8M", url)
                store.flush()
                assertEquals(0, store.stats().physicalBytes)
            }
        }
    }

    @Test
    fun `qemu-img sees the virtual size and read-only images`() {
        val qemuImg = tool("qemu-img")
        assumeTrue(qemuImg != null, "qemu-img not installed")
        ChunkStore(dir).use { store ->
            store.createDisk("big", 100L shl 30)
            store.createDisk("iso", 1L shl 20, readOnly = true)
            NbdServer(store, 0).start().use { nbd ->
                val info = run(qemuImg!!.path, "info", "nbd://127.0.0.1:${nbd.port}/big")
                assertTrue("100 GiB" in info, info)
                val qemuIo = tool("qemu-io") ?: return
                val p = ProcessBuilder(qemuIo.path, "-f", "raw", "-c", "write 0 4k", "nbd://127.0.0.1:${nbd.port}/iso")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(30, TimeUnit.SECONDS)
                assertTrue("ermission" in out || "read-only" in out || "Read-only" in out || p.exitValue() != 0, out)
                assertEquals(0, store.disk("iso")!!.allocatedBytes)
            }
        }
    }
}
