package io.legado.app.help.storage

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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
        val result = linkedMapOf<String, Any?>()
        val warnings = mutableListOf<String>()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // 配置来自备份文件，不允许外部实体、外部 DTD 或自定义 DOCTYPE。
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val nodes = document.documentElement.childNodes
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            when (element.tagName) {
                "string" -> result[name] = element.textContent.orEmpty()
                "set" -> result[name] = element.getElementsByTagName("string").let { strings ->
                    buildSet { for (i in 0 until strings.length) add(strings.item(i).textContent.orEmpty()) }
                }
                "int" -> result[name] = element.getAttribute("value").toInt()
                "long" -> result[name] = element.getAttribute("value").toLong()
                "float" -> result[name] = element.getAttribute("value").toFloat()
                "boolean" -> result[name] = element.getAttribute("value").toBoolean()
                else -> warnings += "不支持的配置类型：${element.tagName}（$name）"
            }
        }
        return BackupConfigDecoding(result, warnings)
    }

    private fun String.escapeXmlText(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun String.escapeXmlAttribute(): String =
        escapeXmlText()
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
