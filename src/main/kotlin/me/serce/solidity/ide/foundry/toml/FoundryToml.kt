package me.serce.solidity.ide.foundry.toml

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.TomlTableHeader

/**
 * What foundry.toml may contain. A key's path is relative to a profile: `[profile.ci] fuzz.runs = 1`,
 * `[profile.ci.fuzz] runs = 1` and the standalone `[fuzz] runs = 1` are all `fuzz.runs`.
 */
object FoundryTomlSchema {
  enum class ValueType { BOOL, INT, STRING, ARRAY, TABLE, ANY }

  const val FILE_NAME = "foundry.toml"

  val EVM_VERSIONS = listOf(
    "homestead", "tangerineWhistle", "spuriousDragon", "byzantium", "constantinople", "petersburg", "istanbul",
    "berlin", "london", "paris", "shanghai", "cancun", "prague", "osaka",
  )

  /** String values forge accepts, by key path. */
  val ENUMS: Map<String, List<String>> = mapOf(
    "evm_version" to EVM_VERSIONS,
    "deny" to listOf("never", "warnings", "notes"),
    "bytecode_hash" to listOf("ipfs", "bzzr1", "none"),
    "revert_strings" to listOf("default", "strip", "debug", "verboseDebug"),
    "eip1559_fee_estimate" to listOf("market", "aggressive"),
    "fmt.style" to listOf("space", "tab"),
    "fmt.int_types" to listOf("long", "short", "preserve"),
    "fmt.multiline_func_header" to listOf(
      "attributes_first", "params_first", "params_first_multi", "all", "all_params", "params_always"
    ),
    "fmt.quote_style" to listOf("double", "single", "preserve"),
    "fmt.number_underscore" to listOf("preserve", "thousands", "remove"),
    "fmt.hex_underscore" to listOf("preserve", "remove", "bytes"),
    "fmt.single_line_statement_blocks" to listOf("preserve", "single", "multi"),
  )

  /** Tables whose keys are the user's own: RPC aliases, etherscan chains, dependencies... */
  private fun isFreeForm(path: List<String>): Boolean {
    val first = path.firstOrNull() ?: return false
    val type = FoundryTomlKeys.PROFILE[first]
    return type == ValueType.TABLE && first !in FoundryTomlKeys.TABLES || path.size > 2
  }

  fun isFoundryToml(file: PsiFile) = file.originalFile.name == FILE_NAME

  /**
   * The path of [table]'s header relative to a profile, or null when it isn't checked: `[profile.ci.fuzz]` and
   * `[fuzz]` are `[fuzz]`, `[profile.ci]` is `[]`. Unknown standalone tables are someone else's (null).
   */
  fun tablePath(table: TomlTable?): List<String>? {
    val segments = table?.header?.key?.segments?.map { it.name ?: return null } ?: return null
    if (segments.firstOrNull() == "profile") return if (segments.size >= 2) segments.drop(2) else null
    return segments.takeIf { it.firstOrNull() in FoundryTomlKeys.TABLES || FoundryTomlKeys.PROFILE[it.firstOrNull()] == ValueType.TABLE }
  }

  /** The keys allowed directly under [path] (relative to a profile), or null when anything goes. */
  fun keysAt(path: List<String>): Map<String, ValueType>? = when {
    path.isEmpty() -> FoundryTomlKeys.PROFILE
    path.size == 1 -> FoundryTomlKeys.TABLES[path[0]]
    else -> null
  }

  fun typeOf(path: List<String>): ValueType? {
    if (path.isEmpty()) return null
    return keysAt(path.dropLast(1))?.get(path.last())
  }

  /** Whether [path] (relative to a profile) is a key forge doesn't know. */
  fun isUnknown(path: List<String>): Boolean {
    if (path.isEmpty()) return false
    for (i in path.indices) {
      val prefix = path.subList(0, i)
      if (prefix.isNotEmpty() && isFreeForm(prefix)) return false
      val keys = keysAt(prefix) ?: return false
      if (path[i] !in keys) return true
    }
    return false
  }

  fun keyPath(keyValue: TomlKeyValue): List<String>? {
    val table = keyValue.parentOfType<TomlTable>() ?: return null
    // a key-value inside an inline table: skip the check, its owner isn't tracked here
    if (keyValue.parent !is TomlTable) return null
    val base = tablePath(table) ?: return null
    val segments = keyValue.key.segments.map { it.name ?: return null }
    return base + segments
  }
}

/** Keys forge doesn't know, in profiles and in the standalone tables forge reads. */
class FoundryTomlUnknownKeyInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!FoundryTomlSchema.isFoundryToml(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return object : PsiElementVisitor() {
      override fun visitElement(element: PsiElement) {
        when (element) {
          is TomlKeyValue -> {
            val path = FoundryTomlSchema.keyPath(element) ?: return
            // under an unknown table header, the header is already reported
            val base = FoundryTomlSchema.tablePath(element.parent as? TomlTable) ?: return
            if (FoundryTomlSchema.isUnknown(base)) return
            if (FoundryTomlSchema.isUnknown(path)) holder.report(element.key, path)
          }
          is TomlTableHeader -> {
            val table = element.parent as? TomlTable ?: return
            val segments = element.key?.segments?.mapNotNull { it.name } ?: return
            if (segments.firstOrNull() != "profile" || segments.size <= 2) return
            val path = FoundryTomlSchema.tablePath(table) ?: return
            if (FoundryTomlSchema.isUnknown(path)) holder.report(element.key!!, path)
          }
        }
      }
    }
  }

  private fun ProblemsHolder.report(key: TomlKey, path: List<String>) {
    val segment = key.segments.lastOrNull() ?: return
    registerProblem(segment, "Unknown foundry.toml key '${path.joinToString(".")}'", ProblemHighlightType.WARNING)
  }
}

/** Keys of profiles and of their tables, table names in headers, and the values of booleans and enum settings. */
class FoundryTomlCompletionContributor : CompletionContributor() {
  override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
    if (!FoundryTomlSchema.isFoundryToml(parameters.originalFile)) return
    val position = parameters.position
    val segment = position.parentOfType<TomlKeySegment>()
    if (segment != null) {
      val key = segment.parent as? TomlKey ?: return
      val before = key.segments.takeWhile { it != segment }.map { it.name ?: return }
      when (val owner = key.parent) {
        is TomlTableHeader -> completeHeader(before, result)
        is TomlKeyValue -> {
          val base = FoundryTomlSchema.tablePath(owner.parent as? TomlTable) ?: return
          addKeys(base + before, result)
        }
      }
      return
    }
    // a value being typed: a literal, or for a bare word (`tr`) an error element inside the key-value
    val literal = position.parentOfType<TomlLiteral>()
    val keyValue = (literal?.parent ?: position.parentOfType<TomlKeyValue>()) as? TomlKeyValue ?: return
    val path = FoundryTomlSchema.keyPath(keyValue) ?: return
    val values = FoundryTomlSchema.ENUMS[path.joinToString(".")]
    val type = FoundryTomlSchema.typeOf(path)
    val valueText = literal?.text ?: position.text
    val quoted = valueText.startsWith("\"") || valueText.startsWith("'")
    when {
      values != null && quoted -> {
        val start = (literal ?: position).textRange.startOffset + 1
        val prefix = parameters.originalFile.text.substring(start, parameters.offset)
        val matcher = result.withPrefixMatcher(prefix)
        values.forEach { matcher.addElement(LookupElementBuilder.create(it)) }
      }
      values != null -> values.forEach { result.addElement(LookupElementBuilder.create("\"$it\"").withLookupString(it)) }
      type == FoundryTomlSchema.ValueType.BOOL && !quoted -> listOf("true", "false").forEach {
        result.addElement(LookupElementBuilder.create(it).bold())
      }
    }
  }

  private fun completeHeader(before: List<String>, result: CompletionResultSet) {
    when {
      before.isEmpty() -> (listOf("profile") + FoundryTomlKeys.TABLES.keys + listOf("rpc_endpoints", "etherscan"))
        .forEach { result.addElement(LookupElementBuilder.create(it)) }
      before.size == 1 && before[0] == "profile" -> listOf("default", "ci").forEach { result.addElement(LookupElementBuilder.create(it)) }
      before.size == 2 && before[0] == "profile" -> FoundryTomlKeys.PROFILE.filterValues { it == FoundryTomlSchema.ValueType.TABLE }.keys
        .plus(FoundryTomlKeys.TABLES.keys).distinct().forEach { result.addElement(LookupElementBuilder.create(it)) }
    }
  }

  private fun addKeys(path: List<String>, result: CompletionResultSet) {
    val keys = FoundryTomlSchema.keysAt(path) ?: return
    keys.forEach { (name, type) -> result.addElement(LookupElementBuilder.create(name).withTypeText(type.name.lowercase())) }
  }
}
