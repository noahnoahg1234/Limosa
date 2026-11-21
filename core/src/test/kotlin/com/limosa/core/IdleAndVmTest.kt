package com.limosa.core

import com.limosa.core.idle.IdleController
import com.limosa.core.json.Json
import com.limosa.core.vm.QemuEnvironment
import com.limosa.core.vm.VmConfig
import com.limosa.core.vm.VmManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdleAndVmTest {
    @TempDir lateinit var dir: File

    @Test
    fun `screen off pauses only after the delay and screen on resumes`() {
        var now = 0L
        val events = ArrayList<String>()
        val c = IdleController(
            IdleController.Settings(screenOffDelaySec = 30),
            onIdle = { events += "idle:$it" },
            onActive = { events += "active" },
            clock = { now },
        )
        c.update { it.copy(screenOn = false) }
        now = 29_000; c.evaluate()
        assertTrue(events.isEmpty())
        now = 30_000; c.evaluate()
        assertEquals(listOf("idle:screen off"), events)
        c.update { it.copy(screenOn = true) }
        assertEquals(listOf("idle:screen off", "active"), events)
        // quick screen blips never pause
        c.update { it.copy(screenOn = false) }
        now = 35_000; c.update { it.copy(screenOn = true) }
        now = 90_000; c.evaluate()
        assertEquals(2, events.size)
    }

    @Test
    fun `doze low battery and settings`() {
        val events = ArrayList<String>()
        val c = IdleController(onIdle = { events += it }, onActive = { events += "active" })
        c.update { it.copy(deviceIdle = true) }
        assertEquals("device idle (Doze)", c.idleReason)
        c.update { it.copy(deviceIdle = false, batteryPercent = 5, charging = false) }
        assertEquals("battery low", c.idleReason)
        c.update { it.copy(charging = true) }
        assertFalse(c.idle)
        c.settings = c.settings.copy(enabled = false)
        c.update { it.copy(deviceIdle = true) }
        assertFalse(c.idle)
        assertEquals(listOf("device idle (Doze)", "active"), events)
    }

    private fun fakeQemu(): QemuEnvironment {
        val script = File(dir, "fake_qemu.py")
        script.writeText(javaClass.getResource("/fake_qemu.py")!!.readText())
        script.setExecutable(true)
        return object : QemuEnvironment {
            override fun binary(arch: VmConfig.Arch) = script
            override fun dataDir(): File? = null
        }
    }

    @Test
    fun `engine pauses VMs when idle and resumes only idle pauses`() {
        assumeTrue(File("/usr/bin/python3").exists())
        val engine = LimosaEngine(File(dir, "data"), fakeQemu(), { null }, httpPort = 0, nbdPort = 0, log = {})
        engine.use { e ->
            e.store.createDisk("hd", 10L shl 30)
            e.vms.save(VmConfig("one", disk = "hd"))
            e.vms.save(VmConfig("two", autoPause = false))
            e.vms.save(VmConfig("three"))
            for (n in listOf("one", "two", "three")) e.vms.start(n)
            waitFor { e.vms.runningVms().all { it.state == VmManager.State.RUNNING } }

            val cmd = e.vms.buildCommand(e.vms.get("one")!!, 0).joinToString(" ")
            assertTrue("nbd://127.0.0.1:${e.nbd.port}/hd" in cmd, cmd)
            assertTrue("discard=unmap" in cmd)

            e.vms.pause("three", VmManager.PauseReason.USER)
            e.idle.settings = e.idle.settings.copy(screenOffDelaySec = 0)
            e.idle.update { it.copy(screenOn = false) }
            assertTrue(e.idle.idle)
            val one = e.vms.running("one")!!
            assertEquals(VmManager.State.PAUSED, one.state)
            assertEquals(VmManager.PauseReason.IDLE, one.pauseReason)
            assertTrue(one.deepSleep, "process should be SIGSTOPped")
            assertEquals(VmManager.State.RUNNING, e.vms.running("two")!!.state, "autoPause=false VM keeps running")

            e.idle.update { it.copy(screenOn = true) }
            assertEquals(VmManager.State.RUNNING, one.state)
            assertFalse(one.deepSleep)
            assertNull(one.pauseReason)
            assertEquals(VmManager.State.PAUSED, e.vms.running("three")!!.state, "user pause is kept")
            waitFor { e.vms.log("one").any { it.contains("qmp: cont") } }

            // the localhost site
            val base = "http://127.0.0.1:${e.http.port}"
            assertEquals(401, http("GET", "$base/api/status").first)
            val (code, body) = http("GET", "$base/api/status", token = e.token)
            assertEquals(200, code)
            val status = Json.parseObject(body)
            assertNotNull(status["storage"])
            val (c2, created) = http("POST", "$base/api/disks", """{"name":"web","sizeGb":32}""", e.token)
            assertEquals(201, c2, created)
            val (c3, put) = http("PUT", "$base/api/disks/cd?readOnly=true", "hello iso", e.token)
            assertEquals(201, c3, put)
            val (c4, part) = http("GET", "$base/disk/cd", token = e.token, range = "bytes=6-8")
            assertEquals(206, c4)
            assertEquals("iso", part)
            val (c5, _) = http("POST", "$base/api/vms/one/pause", token = e.token)
            assertEquals(200, c5)
            assertEquals(VmManager.PauseReason.USER, one.pauseReason)

            e.vms.stop("one")
            waitFor { e.vms.running("one") == null }
        }
    }

    private fun http(method: String, url: String, body: String? = null, token: String? = null, range: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        token?.let { c.setRequestProperty("X-Limosa-Token", it) }
        range?.let { c.setRequestProperty("Range", it) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }
}
