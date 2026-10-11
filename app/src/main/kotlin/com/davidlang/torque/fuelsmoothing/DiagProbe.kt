package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * TEMP diagnostic (revert with its commit): on every ECU connect, run every fuel-source probe (all candidates,
 * brand PIDs regardless of make) and a full identity read, one read-only request per tick, and write each
 * request, raw reply, decoded value, timing and pass/fail reason to files/logs/probe-<ts>.log.
 * Identity is written UNREDACTED (full VIN, calId, CVN, ECU name, raw mode 09) to that app-private file only;
 * nothing here goes to logcat except the file name and step count. Never changes any source, flag or profile.
 */
internal class DiagProbe(
    private val out: File,
    private val io: Io,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    interface Io {
        fun send(header: String, command: String): Array<String>?
        fun protocol(): String?
        fun profileInfo(): Array<String>?
        fun profileData(key: String): String?
        fun setting(key: String): String?
        fun ecuSupported(): Array<String>?
        /** Torque value for a PID long name (null when the name is not in Torque's PID list). */
        fun valueByName(longName: String): Pair<String, Double?>?
    }

    sealed class Step(val label: String) {
        class Cmd(label: String, val header: String, val command: String, val kind: String) : Step(label)
        class Info(label: String) : Step(label)
    }

    val steps: ArrayDeque<Step> = ArrayDeque(plan())
    val total = steps.size
    private var passed = 0

    init { write("=== TEMP diagnostic probe start ${stamp()} steps=$total") }

    /** One step per call; true when finished. */
    fun step(): Boolean {
        val s = steps.removeFirstOrNull() ?: return true
        val t0 = nowMs()
        val line = runCatching { run(s) }.getOrElse { "FAIL exception ${it.javaClass.simpleName}: ${it.message}" }
        if (line.startsWith("PASS")) passed++
        write("${stamp()} [${s.label}] ${line} (${nowMs() - t0} ms)")
        if (steps.isEmpty()) write("=== done ${stamp()} pass=$passed/$total")
        return steps.isEmpty()
    }

    private fun run(s: Step): String = when (s) {
        is Step.Info -> info(s.label)
        is Step.Cmd -> cmd(s)
    }

    private fun info(label: String): String = when {
        label == "protocol" -> io.protocol().let { if (it.isNullOrBlank()) "FAIL protocol empty" else "PASS protocol='$it'" }
        label == "torqueProfile" -> io.profileInfo().let { if (it == null) "FAIL null" else "PASS fields=${it.toList()}" }
        label == "ecuSupported" -> io.ecuSupported().let { if (it == null) "FAIL null" else "PASS n=${it.size} ${it.toList()}" }
        label.startsWith("profileData:") -> label.removePrefix("profileData:").let { k ->
            "PASS retrieveProfileData('$k')='${io.profileData(k)}' getSettingString('$k')='${io.setting(k)}'"
        }
        label.startsWith("name:") -> label.removePrefix("name:").let { n ->
            val r = io.valueByName(n)
            when {
                r == null -> "FAIL '$n' not in Torque PID list"
                r.second == null -> "FAIL '$n' id=${r.first} no value"
                else -> "PASS '$n' id=${r.first} value=${r.second}"
            }
        }
        else -> "FAIL unknown info step"
    }

    private fun cmd(s: Step.Cmd): String {
        val r = io.send(s.header, s.command)
        val raw = r?.toList()
        val rawTxt = "req hdr='${s.header}' cmd=${s.command} raw=${raw ?: "null"}"
        if (r == null) return "FAIL null reply; $rawTxt"
        val joined = r.joinToString(" ").uppercase()
        if (r.isEmpty() || joined.isBlank()) return "FAIL empty reply; $rawTxt"
        for (bad in listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "?", "BUS INIT", "CAN ERROR", "TIMEOUT"))
            if (joined.contains(bad)) return "FAIL reply '$bad'; $rawTxt"
        val hex = joined.replace(" ", "")
        val neg = "7F" + s.command.replace(" ", "").uppercase().take(2)
        if (hex.contains(neg)) return "FAIL negative response ${hex.substring(hex.indexOf(neg)).take(6)}; $rawTxt"
        val marker = marker(s.command)
        if (s.kind in setOf("vin", "cvn", "ascii")) {
            // Multi-frame mode 09: decoded from the whole reply (ISO-TP line prefixes vary).
            val v = decode(s, r, "00")
            return if (v.endsWith("=null")) "FAIL undecodable $v; $rawTxt" else "PASS $v; $rawTxt"
        }
        val data = r.firstNotNullOfOrNull { ObdReply.dataAfter(it, marker, 1)?.takeIf { d -> d.length >= 2 } }
            ?: return "FAIL no positive marker $marker; $rawTxt"
        return "PASS ${decode(s, r, data)}; data=$data; $rawTxt"
    }

    private fun decode(s: Step.Cmd, r: Array<String>, data: String): String {
        val list = r.toList(); val a = data.substring(0, 2).toInt(16)
        return when (s.kind) {
            "pct" -> "level=${"%.2f".format(a * 100.0 / 255.0)}% (A=$a)"
            "gm" -> "level=${FuelSources.gmLevel(data)?.let { "%.2f".format(it) }}% bytes=${data.length / 2}"
            "toyota" -> FuelSources.toyotaLevel(a, "").let { "level=${"%.2f".format(it.pct)}% liters=${it.liters}" }
            "bitmap" -> "bitmap=${Fingerprint.parseBitmap(list, s.command.takeLast(2))}"
            "vin" -> "VIN=${ProfileStore.parseVin(list)}"
            "cvn" -> "CVN=${Fingerprint.parseCvn(list)}"
            "ascii" -> "ascii=${Fingerprint.parseAscii(list, s.command.takeLast(2))}"
            else -> "A=$a"
        }
    }

    private fun write(line: String) {
        runCatching { out.parentFile?.mkdirs(); out.appendText(line + "\n") }
    }

    private fun stamp() = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(nowMs()))

    companion object {
        const val KEEP_FILES = 20

        fun marker(command: String): String {
            val h = command.replace(" ", "").uppercase()
            return when {
                h.startsWith("01") -> "41" + h.substring(2)
                h.startsWith("09") -> "49" + h.substring(2)
                h.startsWith("22") -> "62" + h.substring(2)
                h.startsWith("21") -> "61" + h.substring(2)
                else -> h
            }
        }

        fun plan(): List<DiagProbe.Step> {
            val s = ArrayList<DiagProbe.Step>()
            listOf("protocol", "torqueProfile", "ecuSupported").forEach { s += DiagProbe.Step.Info(it) }
            listOf("tankCapacity", "profileTankCapacity", TankSeed.PROFILE_KEY).forEach { s += DiagProbe.Step.Info("profileData:$it") }
            for (p in listOf("00", "20", "40", "60", "80", "A0", "C0")) s += DiagProbe.Step.Cmd("bitmap01$p", "", "01$p", "bitmap")
            s += DiagProbe.Step.Cmd("mode09 00", "", "0900", "bitmap")
            s += DiagProbe.Step.Cmd("mode09 01", "", "0901", "raw")
            s += DiagProbe.Step.Cmd("VIN 0902", "", "0902", "vin")
            s += DiagProbe.Step.Cmd("mode09 03", "", "0903", "raw")
            s += DiagProbe.Step.Cmd("calId 0904", "", "0904", "ascii")
            s += DiagProbe.Step.Cmd("mode09 05", "", "0905", "raw")
            s += DiagProbe.Step.Cmd("CVN 0906", "", "0906", "cvn")
            s += DiagProbe.Step.Cmd("mode09 07", "", "0907", "raw")
            s += DiagProbe.Step.Cmd("IPT 0908", "", "0908", "raw")
            s += DiagProbe.Step.Cmd("mode09 09", "", "0909", "raw")
            s += DiagProbe.Step.Cmd("ECU name 090A", "", "090A", "ascii")
            s += DiagProbe.Step.Cmd("IPT 090B", "", "090B", "raw")
            // Every fuel candidate, brand PIDs regardless of make; all reads.
            for (src in FuelSources.catalog) {
                src.longName?.let { s += DiagProbe.Step.Info("name:$it") }
                val c = src.modePid ?: continue
                val kind = when { src.litersToPct -> "toyota"; src.twoByteOk -> "gm"; else -> "pct" }
                s += DiagProbe.Step.Cmd("${src.id} $c", src.header ?: "", c, kind)
            }
            s += DiagProbe.Step.Cmd("ford_16c1 bus 2216C1 (no header)", "", "2216C1", "pct")
            s += DiagProbe.Step.Cmd("ford_16c1 bus 2216C1 hdr C410F1 (J1850 PCM)", "C410F1", "2216C1", "pct")
            s += DiagProbe.Step.Cmd("ford_16c1 bus 2216C1 hdr 7E0 (CAN PCM)", "7E0", "2216C1", "pct")
            return s
        }

        fun fileFor(filesDir: File, ms: Long): File = File(filesDir, "logs/probe-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(ms))}.log")

        fun prune(filesDir: File) {
            val fs = File(filesDir, "logs").listFiles { f -> f.name.startsWith("probe-") && f.name.endsWith(".log") } ?: return
            fs.sortedByDescending { it.name }.drop(KEEP_FILES).forEach { it.delete() }
        }
    }
}
