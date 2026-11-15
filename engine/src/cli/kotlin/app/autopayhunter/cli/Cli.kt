package app.autopayhunter.cli

import app.autopayhunter.engine.Anonymizer
import app.autopayhunter.engine.AutoPayEngine
import app.autopayhunter.engine.CorpusFormat
import app.autopayhunter.engine.Report
import app.autopayhunter.engine.Sms
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Run the engine on a real phone export, on your own laptop. Nothing is uploaded anywhere.
 *
 *   autopay-hunter sms-backup.xml              SMS Backup & Restore export (inbox only)
 *   autopay-hunter corpus.txt --today 2026-11-15
 *   --evidence      show every SMS behind each subscription
 *   --share-misses  print the unreadable ones with personal bits masked, safe to paste for a fix
 */
fun main(args: Array<String>) {
    val files = args.filter { !it.startsWith("--") && !it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
    if (files.isEmpty()) {
        System.err.println("Usage: autopay-hunter <sms-backup.xml | corpus.txt> [--today yyyy-mm-dd] [--evidence] [--share-misses]")
        kotlin.system.exitProcess(2)
    }
    val today = args.indexOf("--today").takeIf { it >= 0 }?.let { LocalDate.parse(args[it + 1]) } ?: LocalDate.now()
    val sms = files.flatMap { read(File(it)) }
    val result = AutoPayEngine(today).scan(sms)
    print(Report.render(result, showEvidence = "--evidence" in args))
    if ("--share-misses" in args && result.suspects.isNotEmpty()) {
        println("== Unreadable, masked for sharing ==")
        result.suspects.forEach { println("[${it.sms.sender.replace(Regex("^[A-Z]{2}-"), "XX-")}] ${Anonymizer.clean(it.sms.body)}") }
    }
}

fun read(f: File): List<Sms> = when {
    f.name.endsWith(".xml", ignoreCase = true) -> readSmsBackupXml(f)
    else -> CorpusFormat.parse(f.readText()).map { it.sms }
}

/** SMS Backup & Restore writes <sms address=".." date="epoch ms" type="1" body=".."/>. type 1 = received. */
fun readSmsBackupXml(f: File): List<Sms> {
    val out = mutableListOf<Sms>()
    val r = XMLInputFactory.newInstance().apply { setProperty(XMLInputFactory.SUPPORT_DTD, false) }
        .createXMLStreamReader(f.inputStream(), "UTF-8")
    val zone = ZoneId.of("Asia/Kolkata")
    while (r.hasNext()) {
        if (r.next() == XMLStreamConstants.START_ELEMENT && r.localName == "sms") {
            if (r.getAttributeValue(null, "type") != "1") continue
            val ms = r.getAttributeValue(null, "date")?.toLongOrNull() ?: continue
            out += Sms(
                id = "x${out.size}",
                sender = r.getAttributeValue(null, "address") ?: "",
                body = r.getAttributeValue(null, "body") ?: "",
                receivedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone),
            )
        }
    }
    return out
}
