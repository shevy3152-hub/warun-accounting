package com.warun.accounting.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

object BackupManifestXml {
    fun write(manifest: BackupManifest, output: OutputStream) {
        manifest.validateContract()
        val document = newDocumentBuilderFactory().newDocumentBuilder().newDocument()
        val root = document.createElement("warun-accounting-backup")
        root.setAttribute("formatVersion", manifest.formatVersion.toString())
        root.setAttribute("createdAtEpochMillis", manifest.createdAtEpochMillis.toString())
        root.setAttribute("appVersion", manifest.appVersion)
        root.setAttribute("roomSchemaVersion", manifest.roomSchemaVersion.toString())
        document.appendChild(root)

        root.appendChild(document.entryElement("database", manifest.database))
        root.appendChild(document.createElement("evidence").also { evidenceRoot ->
            manifest.evidence.forEach { item ->
                evidenceRoot.appendChild(document.createElement("item").also { element ->
                    element.setAttribute("id", item.evidenceId)
                    element.setAttribute("storedUri", item.storedUri)
                    element.setAttribute("path", item.archiveEntry.path)
                    element.setAttribute("size", item.archiveEntry.size.toString())
                    element.setAttribute("sha256", item.archiveEntry.sha256)
                })
            }
        })
        root.appendChild(document.createElement("summary").also { summary ->
            summary.setAttribute("dailyReports", manifest.summary.dailyReportCount.toString())
            summary.setAttribute("receipts", manifest.summary.receiptCount.toString())
            summary.setAttribute("expenses", manifest.summary.expenseCount.toString())
            summary.setAttribute("evidence", manifest.summary.evidenceCount.toString())
            summary.setAttribute("cancellations", manifest.summary.cancellationCount.toString())
            summary.setAttribute("prepaidAccounts", manifest.summary.prepaidAccountCount.toString())
            summary.setAttribute(
                "prepaidTransactions",
                manifest.summary.prepaidTransactionCount.toString()
            )
        })

        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "no")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }.transform(DOMSource(document), StreamResult(output))
    }

    fun read(input: InputStream): BackupManifest {
        return try {
            val bytes = input.readBytes()
            if (bytes.size > BackupContract.MaxManifestBytes) {
                backupFail(BackupFailure.ArchiveTooLarge, "Manifest exceeds size limit")
            }
            if (bytes.any { it == 0.toByte() }) {
                backupFail(BackupFailure.InvalidManifest, "Manifest encoding is unsupported")
            }
            val structure = String(bytes, StandardCharsets.ISO_8859_1).uppercase(Locale.ROOT)
            if ("<!DOCTYPE" in structure || "<!ENTITY" in structure) {
                backupFail(BackupFailure.InvalidManifest, "DTD and entities are prohibited")
            }
            val document = newDocumentBuilderFactory().newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
            document.documentElement.normalize()
            parseDocument(document).also(BackupManifest::validateContract)
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            backupFail(BackupFailure.InvalidManifest, "Manifest could not be parsed", error)
        }
    }

    private fun parseDocument(document: Document): BackupManifest {
        val root = document.documentElement
        if (root.tagName != "warun-accounting-backup") {
            backupFail(BackupFailure.InvalidManifest, "Unexpected manifest root")
        }
        val database = root.singleChild("database").toArchiveEntry()
        val evidenceRoot = root.singleChild("evidence")
        val evidence = evidenceRoot.childElements("item").map { element ->
            BackupEvidenceEntry(
                evidenceId = element.requiredAttribute("id"),
                storedUri = element.requiredAttribute("storedUri"),
                archiveEntry = BackupArchiveEntry(
                    path = element.requiredAttribute("path"),
                    size = element.requiredLong("size"),
                    sha256 = element.requiredAttribute("sha256")
                )
            )
        }
        val summary = root.singleChild("summary")
        return BackupManifest(
            formatVersion = root.requiredInt("formatVersion"),
            createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
            appVersion = root.requiredAttribute("appVersion"),
            roomSchemaVersion = root.requiredInt("roomSchemaVersion"),
            database = database,
            evidence = evidence,
            summary = BackupSummary(
                dailyReportCount = summary.requiredLong("dailyReports"),
                receiptCount = summary.requiredLong("receipts"),
                expenseCount = summary.requiredLong("expenses"),
                evidenceCount = summary.requiredLong("evidence"),
                cancellationCount = summary.requiredLong("cancellations"),
                prepaidAccountCount = summary.requiredLong("prepaidAccounts"),
                prepaidTransactionCount = summary.requiredLong("prepaidTransactions")
            )
        )
    }

    private fun Document.entryElement(
        tagName: String,
        entry: BackupArchiveEntry
    ): Element = createElement(tagName).also { element ->
        element.setAttribute("path", entry.path)
        element.setAttribute("size", entry.size.toString())
        element.setAttribute("sha256", entry.sha256)
    }

    private fun Element.toArchiveEntry() = BackupArchiveEntry(
        path = requiredAttribute("path"),
        size = requiredLong("size"),
        sha256 = requiredAttribute("sha256")
    )

    private fun Element.singleChild(tagName: String): Element {
        val elements = childElements(tagName)
        if (elements.size != 1) {
            backupFail(BackupFailure.InvalidManifest, "Expected one $tagName element")
        }
        return elements.single()
    }

    private fun Element.childElements(tagName: String): List<Element> = buildList {
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == tagName) add(child)
        }
    }

    private fun Element.requiredAttribute(name: String): String =
        getAttribute(name).takeIf(String::isNotEmpty)
            ?: backupFail(BackupFailure.InvalidManifest, "Missing $name")

    private fun Element.requiredLong(name: String): Long =
        requiredAttribute(name).toLongOrNull()
            ?: backupFail(BackupFailure.InvalidManifest, "Invalid $name")

    private fun Element.requiredInt(name: String): Int =
        requiredAttribute(name).toIntOrNull()
            ?: backupFail(BackupFailure.InvalidManifest, "Invalid $name")

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
}
