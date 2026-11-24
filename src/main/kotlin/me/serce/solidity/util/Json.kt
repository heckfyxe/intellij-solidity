package me.serce.solidity.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter

/** The plugin's one Jackson mapper: it is thread-safe once configured, so there is no need for more. */
object Json {
  val mapper = ObjectMapper()

  /** Indented output. */
  val pretty: ObjectWriter = mapper.writerWithDefaultPrettyPrinter()
}

/** The text of field [name], or null when it is missing or JSON `null`. */
fun JsonNode.text(name: String): String? = this[name]?.takeIf { !it.isNull }?.asText()
