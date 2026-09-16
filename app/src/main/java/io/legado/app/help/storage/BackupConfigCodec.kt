package io.legado.app.help.storage

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class BackupConfigEncoding(
    val xml: String,
    val writtenKeys: Set<String>,
    val ignoredKeys: Set<String>,
    val unsupportedKeys: Set<String>,
)

data class BackupConfigDecoding(
    val values: Map<String, Any?>,
    val warnings: List<String> = emptyList(),
)

object BackupConfigCodec {

    fun encode(entries: Map<String, Any?>): BackupConfigEncoding {
        val writtenKeys = linkedSetOf<String>()
        val unsupportedKeys = linkedSetOf<String>()
        val body = StringBuilder()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> {
                    writtenKeys += key
                    body.append("    <string name=\"${key.escapeXmlAttribute()}\">${value.escapeXmlText()}</string>\n")
                }
                is Set<*> -> {
                    if (value.all { it is String }) {
                        writtenKeys += key
                        body.append("    <set name=\"${key.escapeXmlAttribute()}\">\n")
                        @Suppress("UNCHECKED_CAST")
                        (value as Set<String>).forEach {
                            body.append("        <string>${it.escapeXmlText()}</string>\n")
                        }
                        body.append("    </set>\n")
                    } else {
                        unsupportedKeys += key
                    }
                }
                is Int -> {
                    writtenKeys += key
                    body.append("    <int name=\"${key.escapeXmlAttribute()}\" value=\"$value\" />\n")
                }
                is Long -> {
                    writtenKeys += key
                    body.append("    <long name=\"${key.escapeXmlAttribute()}\" value=\"$value\" />\n")
                }
                is Float -> {
                    writtenKeys += key
                    body.append("    <float name=\"${key.escapeXmlAttribute()}\" value=\"$value\" />\n")
                }
                is Boolean -> {
                    writtenKeys += key
                    body.append("    <boolean name=\"${key.escapeXmlAttribute()}\" value=\"$value\" />\n")
                }
                else -> unsupportedKeys += key
            }
        }
        return BackupConfigEncoding(
            xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n$body</map>",
            writtenKeys = writtenKeys,
            ignoredKeys = emptySet(),
            unsupportedKeys = unsupportedKeys,
        )
    }

    fun decode(xml: String): Map<String, Any?> {
        return decodeWithReport(xml).values
    }

    fun decodeWithReport(xml: String): BackupConfigDecoding {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser().apply {
            setInput(StringReader(xml))
        }
        val result = linkedMapOf<String, Any?>()
        val warnings = mutableListOf<String>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.getAttributeValue(null, "name")
                if (name != null) {
                    when (parser.name) {
                        "string" -> result[name] = parser.nextText()
                        "set" -> result[name] = readStringSet(parser)
                        "int" -> result[name] = parser.getAttributeValue(null, "value").toInt()
                        "long" -> result[name] = parser.getAttributeValue(null, "value").toLong()
                        "float" -> result[name] = parser.getAttributeValue(null, "value").toFloat()
                        "boolean" -> result[name] = parser.getAttributeValue(null, "value").toBoolean()
                        else -> warnings += "不支持的配置类型：${parser.name}（$name）"
                    }
                }
            }
            eventType = parser.next()
        }
        return BackupConfigDecoding(result, warnings)
    }

    private fun readStringSet(parser: XmlPullParser): Set<String> {
        val values = linkedSetOf<String>()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "string") values += parser.nextText()
                XmlPullParser.END_TAG -> if (parser.name == "set") break
            }
            eventType = parser.next()
        }
        return values
    }

    private fun String.escapeXmlText(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun String.escapeXmlAttribute(): String =
        escapeXmlText()
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
