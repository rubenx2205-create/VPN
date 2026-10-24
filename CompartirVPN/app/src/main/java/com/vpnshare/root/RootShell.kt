package com.vpnshare.root

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shell de root persistente (un único proceso `su`), para no pedir permiso a Magisk/KernelSU
 * en cada comando. Todas las llamadas están serializadas.
 */
object RootShell {
    private const val TAG = "RootShell"

    data class Result(val code: Int, val output: List<String>) {
        val ok get() = code == 0
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    /** true si `su` está disponible y concede uid 0. */
    @Synchronized
    fun isAvailable(): Boolean = try {
        val r = run("id -u")
        r.ok && r.output.lastOrNull()?.trim() == "0"
    } catch (e: Exception) {
        Log.w(TAG, "su no disponible: $e")
        false
    }

    @Synchronized
    private fun ensureOpen() {
        val p = process
        if (p != null && p.isAlive) return
        val np = ProcessBuilder("su").redirectErrorStream(true).start()
        process = np
        writer = OutputStreamWriter(np.outputStream)
        reader = BufferedReader(InputStreamReader(np.inputStream))
    }

    /**
     * Ejecuta un script (puede tener varias líneas) y devuelve el código de salida del último
     * comando y toda la salida combinada (stdout+stderr).
     */
    @Synchronized
    fun run(script: String): Result {
        ensureOpen()
        val marker = "__VPNSHARE_END_${UUID.randomUUID()}__"
        val w = writer!!
        val r = reader!!
        try {
            // El script se ejecuta en un subshell para que un `exit` no mate la sesión.
            w.write("(\n$script\n) 2>&1\necho \"$marker \$?\"\n")
            w.flush()
            val out = ArrayList<String>()
            while (true) {
                val line = r.readLine() ?: throw IllegalStateException("su terminó inesperadamente")
                if (line.startsWith(marker)) {
                    val code = line.substringAfter(marker).trim().toIntOrNull() ?: -1
                    return Result(code, out)
                }
                out.add(line)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized
    fun close() {
        try {
            writer?.write("exit\n")
            writer?.flush()
        } catch (_: Exception) {
        }
        process?.let {
            if (!it.waitFor(500, TimeUnit.MILLISECONDS)) it.destroy()
        }
        process = null
        writer = null
        reader = null
    }
}
