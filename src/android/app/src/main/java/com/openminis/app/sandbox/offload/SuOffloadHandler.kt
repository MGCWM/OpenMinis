package com.openminis.app.sandbox.offload

import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host Magisk / KernelSU `su` passthrough, with Shizuku as fallback.
 *
 * Guest `su` / `android-su` is intercepted by native_offload and executed
 * as the **host** superuser binary (`su -c …`) first. If that binary is
 * missing or Magisk/KernelSU refuses elevation, the same command is retried
 * via Shizuku (`sh -c`) when the binder is ready. Shizuku's own agent
 * permission is not required — `su_cli` is the gate; Shizuku is only the
 * elevation transport. `status` / help / version skip the permission gate.
 */
class SuOffloadHandler : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val parsed = SuCommand.parse(request.argv)
        return when (parsed.kind) {
            SuCommand.Kind.HELP -> NativeOffloadResult(0, HELP)
            SuCommand.Kind.VERSION -> NativeOffloadResult(0, "android-su 1.1 (host Magisk/KernelSU, Shizuku fallback)\n")
            SuCommand.Kind.STATUS -> status()
            SuCommand.Kind.INVALID -> NativeOffloadResult(2, "android-su: missing command. Try `su -c id` or `android-su --help`.\n")
            SuCommand.Kind.EXEC -> {
                if (!OffloadGate.allow("su_cli", "android-su", request)) {
                    val body = JSONObject()
                        .put("error", "permission_denied")
                        .put(
                            "message",
                            "Agent is not allowed to use android-su. Open Settings → Permissions → Host su to change.",
                        )
                        .toString()
                    return NativeOffloadResult(126, body + "\n")
                }
                exec(parsed.command!!, parsed.timeoutMs)
            }
        }
    }

    private fun status(): NativeOffloadResult {
        val path = SuCommand.findSuBinary()
        val shizukuReady = ShizukuManager.isReady()
        val shizukuState = ShizukuManager.snapshot.value.state.name
        val preferred = when {
            path != null -> "host-su"
            shizukuReady -> "shizuku"
            else -> "none"
        }
        val data = JSONObject()
            .put("found", path != null)
            .put("path", path ?: JSONObject.NULL)
            .put("backend", preferred)
            .put("preferred", "host-su")
            .put("fallback", "shizuku")
            .put(
                "host_su",
                JSONObject()
                    .put("found", path != null)
                    .put("path", path ?: JSONObject.NULL),
            )
            .put(
                "shizuku",
                JSONObject()
                    .put("ready", shizukuReady)
                    .put("state", shizukuState),
            )
        if (preferred == "none") {
            data.put(
                "message",
                "No host su binary and Shizuku is not ready. Install Magisk/KernelSU, or authorize Shizuku.",
            )
            return NativeOffloadResult(1, data.toString(2) + "\n")
        }
        return NativeOffloadResult(0, data.toString(2) + "\n")
    }

    private fun exec(command: String, timeoutMs: Long): NativeOffloadResult {
        val su = SuCommand.findSuBinary()
        if (su != null) {
            try {
                val (code, output) = SuCommand.runHost(listOf(su, "-c", command), timeoutMs)
                if (!SuCommand.looksLikeElevationFailure(code, output)) {
                    return NativeOffloadResult(code, output)
                }
                AppLogger.info(TAG, "host su refused elevation (code=$code); trying Shizuku")
                return execViaShizuku(command, timeoutMs, "host su refused elevation")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "su -c failed: ${t.message}")
                return execViaShizuku(command, timeoutMs, t.message ?: "host su failed")
            }
        }
        return execViaShizuku(command, timeoutMs, "host su binary not found")
    }

    private fun execViaShizuku(
        command: String,
        timeoutMs: Long,
        hostReason: String,
    ): NativeOffloadResult {
        if (!ShizukuManager.isReady()) {
            val state = ShizukuManager.snapshot.value.state.name
            return NativeOffloadResult(
                1,
                "android-su: $hostReason. Shizuku fallback unavailable (state=$state). " +
                    "Install Magisk/KernelSU, or authorize Shizuku in Settings → Permissions.\n",
            )
        }
        val r = ShizukuManager.runProcess(arrayOf("sh", "-c", command), timeoutMs = timeoutMs)
        val body = r.combined.let { if (it.endsWith("\n") || it.isEmpty()) it else it + "\n" }
        return NativeOffloadResult(r.exitCode, body)
    }

    companion object {
        private const val TAG = "SuOffload"
        private const val HELP = """android-su — privileged host command (Magisk/KernelSU, then Shizuku)

This is NOT the guest fake-root inside PRoot. Prefers the device's real
`su` binary (`su -c <command>`). If that binary is missing or Magisk
denies elevation, retries the same command via Shizuku when it is ready.

Usage:
  su -c <command>                 privileged host command
  android-su -c <command>         same
  android-su exec <command>       same (joins remaining argv)
  android-su status               host su + Shizuku readiness (no Magisk prompt)
  android-su --help

Options:
  --timeout <seconds>             kill the host process after N seconds (default 60)

Examples:
  su -c id
  su -c 'ls /data'
  android-su exec getprop ro.build.version.release
"""
    }
}

/** Pure command parser + host su locator, unit-tested without a device. */
internal object SuCommand {
    enum class Kind { HELP, VERSION, STATUS, EXEC, INVALID }

    data class Parsed(
        val kind: Kind,
        val command: String? = null,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    )

    const val DEFAULT_TIMEOUT_MS = 60_000L

    val SU_CANDIDATES: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/system/bin/ksu",
        "/system/xbin/ksu",
    )

    fun parse(argv: List<String>): Parsed {
        val args = argv.drop(1)
        if (args.isEmpty()) return Parsed(Kind.HELP)
        var timeoutMs = DEFAULT_TIMEOUT_MS
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-h", "--help", "help" -> return Parsed(Kind.HELP, timeoutMs = timeoutMs)
                "--version", "version" -> return Parsed(Kind.VERSION, timeoutMs = timeoutMs)
                "status", "ping" -> return Parsed(Kind.STATUS, timeoutMs = timeoutMs)
                "--timeout" -> {
                    val secs = args.getOrNull(i + 1)?.toLongOrNull()
                    if (secs != null && secs > 0) timeoutMs = secs * 1000L
                    i += 2
                    continue
                }
                "-c", "--command" -> {
                    val cmd = args.getOrNull(i + 1)
                    return if (cmd.isNullOrBlank()) Parsed(Kind.INVALID, timeoutMs = timeoutMs)
                    else Parsed(Kind.EXEC, command = cmd, timeoutMs = timeoutMs)
                }
                "exec" -> {
                    val cmd = args.drop(i + 1).joinToString(" ").trim()
                    return if (cmd.isEmpty()) Parsed(Kind.INVALID, timeoutMs = timeoutMs)
                    else Parsed(Kind.EXEC, command = cmd, timeoutMs = timeoutMs)
                }
                else -> {
                    if (a.startsWith("-")) {
                        i++
                        continue
                    }
                    val cmd = args.drop(i).joinToString(" ").trim()
                    return if (cmd.isEmpty()) Parsed(Kind.INVALID, timeoutMs = timeoutMs)
                    else Parsed(Kind.EXEC, command = cmd, timeoutMs = timeoutMs)
                }
            }
        }
        return Parsed(Kind.INVALID, timeoutMs = timeoutMs)
    }

    fun findSuBinary(): String? {
        for (path in SU_CANDIDATES) {
            val f = File(path)
            if (f.exists() && f.canExecute()) return path
        }
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", "command -v su").start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            out.takeIf { it.isNotEmpty() && File(it).canExecute() }
        } catch (_: Throwable) {
            null
        }
    }

    fun runHost(argv: List<String>, timeoutMs: Long): Pair<Int, String> {
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val buf = ByteArrayOutputStream()
        val reader = Thread {
            try {
                proc.inputStream.copyTo(buf)
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            reader.join(1_000)
            return 124 to "android-su: timed out after ${timeoutMs}ms\n"
        }
        reader.join(1_000)
        val text = buf.toString(Charsets.UTF_8.name())
        return proc.exitValue() to text
    }

    /**
     * True when the host `su` wrapper itself refused elevation — not when the
     * inner command ran as root and then failed. Timeouts are not elevation
     * failures (the command was already running).
     */
    fun looksLikeElevationFailure(exitCode: Int, output: String): Boolean {
        if (exitCode == 0 || exitCode == 124) return false
        val t = output.trim()
        // Wrapper denials are short. A real command that ran as root can also
        // print "Permission denied"; don't retry those via Shizuku (would
        // execute the payload twice).
        if (t.length > 200) return false
        if (t.lineSequence().count { it.isNotBlank() } > 2) return false
        if (exitCode == 255) return true
        if (exitCode == 1 && t.isEmpty()) return true
        val low = t.lowercase()
        if (low == "permission denied" || low == "access denied") return true
        val deny = listOf(
            "not allowed to su",
            "can't get permission",
            "cannot get permission",
            "authentication failed",
            "no su program",
            "not rooted",
        )
        if (deny.any { low.contains(it) }) return true
        return low.startsWith("su:")
    }
}
