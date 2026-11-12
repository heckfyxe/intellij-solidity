package me.serce.solidity.lang.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.*
import me.serce.solidity.lang.core.SolidityFile
import me.serce.solidity.lang.core.SolidityTokenTypes
import me.serce.solidity.lang.psi.*
import me.serce.solidity.lang.psi.SolImportAlias
import me.serce.solidity.lang.psi.parentOfType
import me.serce.solidity.lang.resolve.ref.SolFunctionCallReference
import me.serce.solidity.lang.resolve.ref.SolReference
import me.serce.solidity.lang.stubs.SolGotoClassIndex
import me.serce.solidity.lang.stubs.SolNamedElementIndex
import me.serce.solidity.lang.types.*
import me.serce.solidity.wrap

object SolResolver {
  fun resolveTypeNameUsingImports(element: PsiElement): Set<SolNamedElement> {
    return resolveTypeNameUsingImportsWithFunctions(element).filter { it !is SolFunctionDefinition }.toSet()
  }

  fun resolveTypeNameUsingImportsWithFunctions(element: PsiElement): Set<SolNamedElement> {
    return RecursionManager.doPreventingRecursion(element, true) {
      CachedValuesManager.getCachedValue(element) {
        val file: PsiFile = element.containingFile
        val elementIdentifiers: List<PsiElement> = when (element) {
          is SolMemberAccessExpression -> {
            getIdentifiersFromMemberAccessExpression(element)
          }

          is SolFunctionCallElement -> {
            listOf(element.firstChild)
          }

          is SolUserDefinedTypeName -> {
            element.findIdentifiers()
          }

          else -> {
            if (element.prevSibling != null && element.prevSibling.text == ".") {
              var currentElement = element.prevSibling
              val list = mutableListOf(element)
              do {
                if (currentElement.elementType == SolidityTokenTypes.IDENTIFIER) {
                  list.add(currentElement)
                } else if (currentElement.elementType != SolidityTokenTypes.DOT) {
                  break
                }
                currentElement = currentElement.prevSibling
              } while (currentElement != null)
              list.reversed()
            } else {
              listOf(element)
            }
          }
        }

        val identifiedElements: Set<SolNamedElement> =
          resolveElementInFileAndImports(elementIdentifiers, file, emptySet())
        CachedValueProvider.Result.create(identifiedElements, PsiModificationTracker.MODIFICATION_COUNT)
      }
    } ?: emptySet()
  }

  private fun resolveElementInFileAndImports(
    elementIdentifiers: List<PsiElement>, file: PsiFile, previouslyIdentifiedElements: Set<SolNamedElement>
  ): Set<SolNamedElement> {
    val currentIdentifier = elementIdentifiers.first()

    var (identifiedElements, nextFile) = resolveCurrentIdentifier(
      currentIdentifier, previouslyIdentifiedElements, file
    )
    val contractAccessElement =
      identifiedElements.find { it is SolStateVariableDeclaration && it.firstChild is SolUserDefinedTypeName }
    if (contractAccessElement != null && nextFile != null) {
      val newElement = resolveTypeNameUsingImportsWithFunctions(contractAccessElement.firstChild)
      if (newElement.isNotEmpty()) {
        identifiedElements = setOf(newElement.first())
        nextFile = newElement.first().containingFile
      }
    }

    return if (elementIdentifiers.size > 1 && identifiedElements.isNotEmpty() && nextFile != null) {
      resolveElementInFileAndImports(elementIdentifiers.drop(1), nextFile, identifiedElements)
    } else if (elementIdentifiers.size > 1 && identifiedElements.isEmpty() && nextFile == null) {
      resolveElementInFileAndImports(elementIdentifiers.drop(1), file, previouslyIdentifiedElements)
    } else {
      identifiedElements
    }
  }

  private fun resolveCurrentIdentifier(
    element: PsiElement, previousElements: Set<SolNamedElement>, currentFile: PsiFile
  ): Pair<Set<SolNamedElement>, PsiFile?> {
    val identifier = identifierFor(element) ?: return emptySet<SolNamedElement>() to null
    val imports = collectImports(currentFile)
    val foundInImport = imports.firstNotNullOfOrNull { importRecord ->
      importRecord.names.find { it.name == identifier }?.let { it to importRecord }
    }

    return when {
      foundInImport != null && foundInImport.first is SolImportAlias -> handleImportAlias(
        foundInImport, currentFile
      )

      foundInImport != null -> setOf(foundInImport.first) to foundInImport.second.file

      else -> resolveFromPreviousOrStub(identifier, previousElements, imports, currentFile)
    }
  }

  private fun identifierFor(element: PsiElement): String? = when (element) {
    is SolReferenceElement -> element.referenceName
    is PsiNamedElement -> element.name
    else -> element.text
  }

  private fun handleImportAlias(
    found: Pair<SolNamedElement, ImportRecord>, currentFile: PsiFile
  ): Pair<Set<SolNamedElement>, PsiFile?> {
    val (alias, importRecord) = found
    val elements = when {
      isAliasOfFile(alias as SolImportAlias) -> setOf(alias)
      else -> resolveElementFromAlias(alias, importRecord, currentFile)
    }
    return elements to importRecord.file
  }

  private fun resolveElementFromAlias(
    alias: SolImportAlias, importRecord: ImportRecord, currentFile: PsiFile
  ): Set<SolNamedElement> {
    val elementFromAlias = (alias.parent as SolImportAliasedPair).userDefinedTypeName
    val findElementFromNames = importRecord.names.find { it.name == elementFromAlias.nameOrText }

    return if (findElementFromNames != null) {
      setOf(findElementFromNames)
    } else {
      val filesOfScope = setOf(importRecord.file.virtualFile)
      searchElementByStub(elementFromAlias.nameOrText!!, filesOfScope, currentFile.project)
    }
  }

  private fun resolveFromPreviousOrStub(
    identifier: String, previousElements: Set<SolNamedElement>, imports: Collection<ImportRecord>, currentFile: PsiFile
  ): Pair<Set<SolNamedElement>, PsiFile?> {
    val resolvedFromPrevious =
      previousElements.filterNot { it is SolImportAlias }.flatMap { it.childrenOfType<SolNamedElement>() }
        .filter { it.name == identifier }

    if (resolvedFromPrevious.isNotEmpty()) {
      val elements = resolvedFromPrevious.toSet()
      return elements to elements.first().containingFile
    }

    // Fall back to stub search
    val importsWithoutFileAliases = imports.filter { importRecord ->
      importRecord.names.none { it is SolImportAlias && isAliasOfFile(it) }
    }
    val filesOfScope =
      setOfNotNull(currentFile.virtualFile) + importsWithoutFileAliases.mapNotNull { it.file.virtualFile }
    // `import {A} from "x.sol"` makes only A visible, though members of A (such as its structs) stay reachable
    val selective = importsWithoutFileAliases.filter { it.names.isNotEmpty() }
    val fullyImported = importsWithoutFileAliases.filter { it.names.isEmpty() }.mapTo(hashSetOf()) { it.file } + currentFile
    val elements = searchElementByStub(identifier, filesOfScope, currentFile.project).filterTo(hashSetOf()) { element ->
      element.parent !is PsiFile || element.containingFile in fullyImported ||
        selective.none { it.file == element.containingFile && element !in it.names }
    }

    return if (elements.isNotEmpty()) {
      elements to elements.first().containingFile
    } else {
      emptySet<SolNamedElement>() to null
    }
  }

  private fun getIdentifiersFromMemberAccessExpression(element: SolMemberAccessExpression): List<PsiElement> {
    val identifiers = mutableListOf<PsiElement>()
    var currentElement: PsiElement = element
    do {
      if (currentElement is SolMemberAccessExpression) {
        identifiers.add(currentElement.identifier!!)
      } else {
        identifiers.add(currentElement)
      }
      currentElement = currentElement.firstChild
    } while (currentElement is SolMemberAccessExpression || currentElement is SolPrimaryExpression)
    //reversed because the identifier starts from the end of the expression
    return identifiers.reversed()
  }

  private fun searchElementByStub(
    identifierToFind: String, filesOfScope: Set<VirtualFile>, project: Project
  ): Set<SolNamedElement> {
    val scope = filesOfScope.takeIf { it.isNotEmpty() }?.let { GlobalSearchScope.filesScope(project, it) }
    return StubIndex.getElements(
      SolNamedElementIndex.KEY, identifierToFind, project, scope, SolNamedElement::class.java
    ).toSet()
  }

  data class ImportRecord(val file: PsiFile, val names: List<SolNamedElement>)

  private val exportElements = setOf(
    SolContractDefinition::class.java,
    SolConstantVariableDeclaration::class.java,
    SolEnumDefinition::class.java,
    SolErrorDefinition::class.java,
    SolFunctionDefinition::class.java,
    SolStructDefinition::class.java,
    SolUserDefinedValueTypeDefinition::class.java,
  )

  fun collectUsedElements(o: SolImportDirective): List<String> {
    val containingFile = o.containingFile
    val importedVFiles = collectImports(o).asSequence().mapNotNull { it.file.virtualFile }.toSet()

    // Be conservative when import targets are unresolved and avoid false "unused import" positives.
    if (importedVFiles.isEmpty()) {
      return listOf(o.text)
    }

    val importedNames = collectImportedNames(containingFile)
    fun PsiElement.outerIdentifier() = (this as? SolUserDefinedTypeName)?.findIdentifiers()?.takeIf { it.size == 2 }?.firstOrNull()?.nameOrText
    fun SolNamedElement.isExportedImportElement() = isExportElement(outerContract() ?: this)

    val used = importedNames.asSequence()
      .filter { importedName -> importedName.target.containingFile.virtualFile?.let(importedVFiles::contains) == true }
      .filter { it.target.isExportedImportElement() }
      .flatMap { imported ->
        sequenceOf(
          imported.target.outerContract()?.name,
          imported.target.name,
          imported.ref.name,
          imported.ref.outerIdentifier()
        ).filterNotNull()
      }
      .toSet()

    val specificNames = o.importAliasedPairList.asSequence().flatMap { aliasedPair ->
      sequenceOf(
        aliasedPair.importAlias?.name,
        aliasedPair.userDefinedTypeName.name
      ).filterNotNull()
    }.toSet()

    return (used.takeIf { specificNames.isEmpty() } ?: used.filter { it in specificNames }).toList()
  }

  fun collectImportedNames(root: PsiFile): Set<ImportedName> {
    return CachedValuesManager.getCachedValue(root) {
      val result = root.descendants().filter { it is SolUserDefinedTypeName && it.parentOfType<SolImportDirective>() == null || it is SolVarLiteral }
                  .flatMap { ref -> (ref.reference as? SolReference)?.multiResolve()?.mapNotNull { Pair(ref as? SolNamedElement ?: return@mapNotNull null, it as? SolNamedElement ?: return@mapNotNull null) } ?: emptyList() }
                  .mapNotNull { (ref, it) ->
                    ImportedName(ref, when {
                        it.isBuiltin() -> null
                        it is SolConstructorDefinition -> it.findContract()
                        it.containingFile != root -> it
                        else -> (it.parent as? SolImportAliasedPair)?.userDefinedTypeName
                    } ?: return@mapNotNull null)
                  }.toSet()
      CachedValueProvider.Result.create(result, root)
    }
  }

  data class ImportedName(val ref: SolNamedElement, val target: SolNamedElement)

  private fun isExportElement(element: PsiElement) = exportElements.any { it.isAssignableFrom(element.javaClass) }

  /** The file an import directive points at, or null when the path doesn't resolve. */
  private val SolImportDirective.importedFile: PsiFile?
    get() = importPath?.reference?.resolve()?.containingFile

  fun collectImports(file: PsiFile): Collection<ImportRecord> =
    file.childrenOfType<SolImportDirective>().flatMapTo(hashSetOf()) { collectImports(it) }

  fun collectImports(import: SolImportDirective): Collection<ImportRecord> {
    return CachedValuesManager.getCachedValue(import) {
      val result = collectImports(listOf(import))
      CachedValueProvider.Result.create(result, result.map { it.file } + import)
    }
  }

  private fun collectImports(imports: Collection<SolImportDirective>): Collection<ImportRecord> {
    return RecursionManager.doPreventingRecursion(imports, true) {
      val visited: MutableSet<PsiFile> = hashSetOf()
      collectImports(imports, visited)
    } ?: emptySet()
  }

  /**
   * Collects imports of all declarations for a given file recursively.
   */
  private fun collectImports(
    imports: Collection<SolImportDirective>, visited: MutableSet<PsiFile>
  ): Collection<ImportRecord> {
    if (!visited.add((imports.firstOrNull() ?: return emptyList()).containingFile)) {
      return emptySet()
    }

    val result = imports.mapNotNull { import ->
      val containingFile = import.importedFile ?: return@mapNotNull null
      val aliases = import.importAliasedPairList
      val names = if (aliases.isNotEmpty() || import.importAlias != null) {
        val exportedDeclarations = containingFile.children.filterIsInstance<SolNamedElement>().filter(::isExportElement)
        aliases.mapNotNull { it.importAlias } + aliases.mapNotNull { importAliasPair ->
          importAliasPair.userDefinedTypeName.name?.let { tn ->
            exportedDeclarations.find { it.name == tn }
          }
        } + listOfNotNull(import.importAlias)
      } else {
        // no alias restrictions
        emptyList()
      }
      ImportRecord(containingFile, names)
    }

    return result + result.map { record ->
      val contractsOrLibsInFile = record.file.childrenOfType<SolContractDefinition>()
      //support only with one contract or lib by file
      // selective and aliased imports don't bring in what the imported file imports itself
      if (contractsOrLibsInFile.size == 1 && isExternalLibrary(contractsOrLibsInFile.first()) || record.names.isNotEmpty()) {
        emptyList()
      } else {
        collectImports(record.file.childrenOfType<SolImportDirective>(), visited)
      }
    }.flatten()
  }

  private fun isExternalLibrary(element: SolContractDefinition): Boolean {
    return (element.contractType == ContractType.LIBRARY
      && element.functionDefinitionList.isNotEmpty()
      && element.functionDefinitionList.all { function ->
        function.visibility == Visibility.EXTERNAL
          || function.visibility == Visibility.PUBLIC
    })
  }

  //collect all SolContractDefinition recursively from imports
  fun collectContracts(file: PsiFile): Collection<SolContractDefinition> {
    return collectImports(file).flatMap { it.file.childrenOfType<SolContractDefinition>() }+ file.childrenOfType<SolContractDefinition>()
  }

  fun collectChildrenOfFile(file: PsiFile): Collection<SolCallable> {
    return collectImports(file).flatMap { it.file.childrenOfType<SolCallableElement>() }+ file.childrenOfType<SolCallableElement>()
  }

  fun collectImportDirective(import: SolImportDirective): Collection<SolImportDirective> {
    return collectImports(import).flatMap { it.file.childrenOfType<SolImportDirective>() }
  }

  fun collectUsingForElementFromImports(
    psiFile: PsiFile,
  ): Collection<SolUsingForDeclaration> {
    return CachedValuesManager.getCachedValue(psiFile) {
      val res = collectUsingForElementFromImports0(psiFile, hashSetOf())
      CachedValueProvider.Result.create(res, res.map { it.containingFile } + psiFile)
    }
  }

  private fun collectUsingForElementFromImports0(
    psiFile: PsiFile,
    visited: MutableSet<PsiFile>
  ): Collection<SolUsingForDeclaration> {
    if (!visited.add(psiFile)) {
      return emptySet()
    }
    return psiFile.childrenOfType<SolUsingForDeclaration>() + psiFile.childrenOfType<SolContractDefinition>()
      .flatMap { contract ->
        contract.usingForDeclarationList
      } + psiFile.childrenOfType<SolImportDirective>().flatMap { import ->
      // an unresolved import contributes nothing; it must not discard the declarations collected from the rest
      import.importedFile?.let { collectUsingForElementFromImports0(it, visited) }.orEmpty()
    }
  }

  private val PsiElement.nameOrText
    get() = if (this is PsiNamedElement) name else text

  fun resolveTypeName(element: SolReferenceElement): Collection<SolNamedElement> = StubIndex.getElements(
    SolGotoClassIndex.KEY,
    element.referenceName,
    element.project,
    null,
    SolNamedElement::class.java
  )

  private fun resolveTypeNameStrict(element: SolReferenceElement) : Collection<SolNamedElement> {
    val names = resolveTypeName(element)
    return names.takeIf { it.size <= 1 } ?: resolveTypeNameUsingImports(element)
  }

  fun resolveVarLiteralReference(element: SolNamedElement): List<SolNamedElement> {
    return when {
        element.parent?.parent is SolFunctionCallExpression -> {
          val functionCall = element.findParentOrNull<SolFunctionCallElement>()!!
          val resolved = functionCall.reference?.multiResolve() ?: emptyList()
          if (resolved.isNotEmpty()) {
            resolved.filterIsInstance<SolNamedElement>()
          } else {
            resolveVarLiteral(element)
          }
        }
        element.parent is SolModifierInvocation -> {
          val modifierInvocation = element.parent as SolModifierInvocation
          fun resolveModifier() = modifierInvocation.reference?.multiResolve()?.filterIsInstance<SolNamedElement>()?.takeIf { it.isNotEmpty() }
          when {
            element.parent.parent is SolConstructorDefinition -> element.findContract()?.collectSupers?.filter { resolveTypeNameStrict(it).filterIsInstance<SolContractDefinition>().any { it.name == element.name } }?.takeIf { it.isNotEmpty() } ?: resolveModifier()
            else -> resolveModifier()
          } ?: emptyList()
        }
        else -> {
          resolveVarLiteral(element)
            .findBest {
              when (it) {
                is SolVariableDeclaration -> 1
                is SolParameterDef -> 10
                is SolStateVariableDeclaration -> 100
                else -> Int.MAX_VALUE
              }
            }
        }
    }
  }

  fun resolveVarLiteral(element: SolNamedElement): List<SolNamedElement> {
    return when (element.name) {
      "this" -> element.findContract()
        .wrap()
      "super" -> element.findContract()
        ?.supers
        ?.flatMap { resolveTypeNameUsingImports(it) }
        ?: emptyList()
      else -> {
        var elementToSearch = element
        val importAlias = resolveAlias(element)
        if (importAlias != null) {
          elementToSearch = getElementFromAlias(element, importAlias)
          //if it has the same name, then we can only link to the alias of the contract
          if (elementToSearch.name == element.name) {
            return listOf(elementToSearch)
          }
        }

        lexicalDeclarations(element)
          .filter { it.name == elementToSearch.name }
          .toList().let {
            if (it.size == 1 || it.any { it !is SolContractDefinition }) it
            // resolve by imports to distinguish elements with the same name
            else resolveTypeNameUsingImports(element).filterNot { found ->
              found.isPrivateMember() && found.findContract() != element.findContract()
            }
          }
      }
    }
  }

  fun resolveAlias(element: SolNamedElement): SolImportDirective? {
    return element.containingFile.childrenOfType<SolImportDirective>().find {
      it.importAlias?.name == element.name ||
              it.importAliasedPairList.any { importAliasedPair ->
                importAliasedPair.importAlias?.name == element.name
              }
    }
  }

  fun isAliasOfFile(import: SolImportDirective): Boolean = import.importAlias != null

  fun isAliasOfFile(alias: SolImportAlias): Boolean = alias.parent !is SolImportAliasedPair

  //return the right element to search
  private fun getElementFromAlias(element: SolNamedElement, import: SolImportDirective): SolNamedElement {
    //match import * as A from "path"
    //match import "path" as A
    import.importAlias?.takeIf { it.name == element.name }?.let { return it }
    //match import {a as A} from "path"
    return import.importAliasedPairList.firstOrNull { it.importAlias?.name == element.name }?.userDefinedTypeName
      ?: element
  }

  fun resolveMemberFunctions(expression: SolMemberAccessExpression): Collection<SolCallable> {
    val name = expression.identifier?.text

    val importDirectiveAlias = expression.childOfType<SolPrimaryExpression>()
      .let { it?.varLiteral?.let { varLiteral -> resolveAlias(varLiteral) } }

    return if (importDirectiveAlias != null && name != null) {
      val penultimateText = expression.firstChild.lastChild.text
      //if true, then it's a file level resolution like fileAlias.element
      if (importDirectiveAlias.importAlias?.text == penultimateText) {
        val fileToSearch = importDirectiveAlias.importedFile ?: return emptyList()
        return collectChildrenOfFile(fileToSearch).filter { elem -> elem.getName() == name }
      }
      //need to check if the penultimate member is an alias of file or a contract to know how to resolve the last member
      val importPenultimateMember =
        collectImportDirective(importDirectiveAlias).firstOrNull { it.importAlias?.text == penultimateText }
      if (importPenultimateMember != null) {
        val fileToSearch = importPenultimateMember.importedFile ?: return emptyList()
        collectChildrenOfFile(fileToSearch).filter { elem -> elem.getName() == name }
      } else {
        //looking to resolve member of a contract
        //first need to find the contract name
        val contractToLook = when (expression.firstChild) {
          is SolMemberAccessExpression -> expression.firstChild.lastChild.text
          is SolFunctionCallExpression -> expression.childOfType<SolMemberAccessExpression>()?.lastChild?.text
          else -> null
        }

        //resolve member
        val fileToSearch = importDirectiveAlias.importedFile ?: return emptyList()
        collectContracts(fileToSearch).filter { contract -> contract.name == contractToLook }.flatMap {
          resolveContractMembers(it).filterIsInstance<SolCallable>().filter { member -> member.getName() == name }
        }
      }
    } else if (name != null) {
      expression.getMembers().filterIsInstance<SolCallable>().filter { it.getName() == name }
    } else {
      emptyList()
    }
  }

  fun resolveMemberAccess(element: SolMemberAccessExpression): List<SolMember> {
    if (element.parent is SolFunctionCallExpression) {
      val functionCall = element.findParentOrNull<SolFunctionCallElement>()!!
      val resolved = (functionCall.reference as SolFunctionCallReference)
        .resolveFunctionCallAndFilter()
        .filterIsInstance<SolMember>()
      if (resolved.isNotEmpty()) {
        return resolved
      }
    }
    val isFunctionCall = element.nextSibling is SolFunctionInvocation

    return when (val memberName = element.identifier?.text) {
      null -> emptyList()
      else -> element.getMembers()
        .filter { it.getName() == memberName && if (isFunctionCall) it !is SolFunctionReference else it !is SolFunctionDefinition}
    }
  }

  /**
   * Members of [contract] followed by those of its bases (recursively). Member access on a contract type goes through
   * here on every highlighting/completion pass, so the full list is cached per contract.
   */
  fun resolveContractMembers(contract: SolContractDefinition, skipThis: Boolean = false): List<SolMember> {
    if (skipThis) {
      return inheritedMembers(contract)
    }
    return CachedValuesManager.getCachedValue(contract) {
      val functions = contract.functionDefinitionList
      val isLibrary = contract.contractType == ContractType.LIBRARY
      val members: List<SolMember> = contract.stateVariableDeclarationList + functions +
        functions.filter { it.visibility.isExternallyReferenceable(isLibrary) }.map { SolFunctionReference(it) } +
        contract.enumDefinitionList.map { SolEnum(it) } +
        contract.structDefinitionList.map { SolMemberConstructor(it) } +
        contract.eventDefinitionList.map { SolMemberConstructor(it) } +
        contract.errorDefinitionList.map { SolMemberConstructor(it) } +
        inheritedMembers(contract)
      CachedValueProvider.Result.create(members, PsiModificationTracker.MODIFICATION_COUNT)
    }
  }

  private fun inheritedMembers(contract: SolContractDefinition): List<SolMember> = contract.supers
    .map { resolveTypeNameStrict(it).firstOrNull() }
    .filterIsInstance<SolContractDefinition>()
    .flatMap { resolveContractMembers(it) }


  fun resolveUsingForElement(element: SolUserDefinedTypeNameElement): SolCallableElement? {
    val identifiers = element.findIdentifiers()
    val first = identifiers.first()
    val lexicalFinding = lexicalDeclarations(first).filterIsInstance<SolCallableElement>()
      .firstOrNull { it.name == first.text }
    return if (identifiers.size > 1 && lexicalFinding is SolContractDefinition) {
      lexicalFinding.functionDefinitionList.find { function -> function.name == identifiers[1].text }
    } else {
      lexicalFinding
    }
  }

  fun lexicalDeclarations(place: PsiElement, stop: (PsiElement) -> Boolean = { false }): Sequence<SolNamedElement> {
    val visitedScopes = hashSetOf<Pair<PsiElement, PsiElement>>()
    return lexicalDeclarations0(visitedScopes, place, stop)
  }

  private fun lexicalDeclarations0(
    visitedScopes: HashSet<Pair<PsiElement, PsiElement>>,
    place: PsiElement,
    stop: (PsiElement) -> Boolean = { false }
  ): Sequence<SolNamedElement> {
    val globalType = SolInternalTypeFactory.of(place.project).globalType
    return lexicalDeclarations(visitedScopes, globalType.ref, place) + lexicalDeclRec(visitedScopes, place, stop).distinct()
  }

  private fun lexicalDeclRec(
    visitedScopes: HashSet<Pair<PsiElement, PsiElement>>,
    place: PsiElement,
    stop: (PsiElement) -> Boolean
  ): Sequence<SolNamedElement> {
    return place.ancestors
      .drop(1) // current element might not be a SolElement
      .takeWhileInclusive { it is SolElement && !stop(it) }
      .flatMap { lexicalDeclarations(visitedScopes, it, place) }
  }

  private fun lexicalDeclarations(
    visitedScopes: HashSet<Pair<PsiElement, PsiElement>>,
    scope: PsiElement,
    place: PsiElement
  ): Sequence<SolNamedElement> {
    // Note that in some cases, loops are possible to encounter when searching for definitions.
    // To avoid the issue, ensure that we only visit place that haven't been visited before.
    if (!visitedScopes.add(scope to place)) {
      return emptySequence()
    }
    return when (scope) {
      is SolVariableDeclaration -> {
        scope.declarationList?.declarationItemList?.filterIsInstance<SolNamedElement>()?.asSequence()
          ?: scope.typedDeclarationList?.typedDeclarationItemList?.filterIsInstance<SolNamedElement>()?.asSequence()
          ?: sequenceOf(scope)
      }

      is SolVariableDefinition -> lexicalDeclarations(visitedScopes, scope.firstChild, place)

      is SolStateVariableDeclaration -> sequenceOf(scope)
      is SolContractDefinition -> {
        val childrenScope = sequenceOf(
          scope.stateVariableDeclarationList as List<PsiElement>,
          scope.enumDefinitionList,
          scope.structDefinitionList
        ).flatten()
          .map { lexicalDeclarations(visitedScopes, it, place) }
          .flatten() + scope.structDefinitionList + scope.eventDefinitionList + scope.errorDefinitionList + scope.userDefinedValueTypeDefinitionList
        val extendsScope = scope.supers.asSequence()
          .map { resolveTypeNameStrict(it).firstOrNull() }
          .filterNotNull()
          .map { lexicalDeclarations(visitedScopes, it, place) }
          .flatten()
          .filterNot { it.isPrivateMember() } // private members are visible only inside the contract declaring them
        childrenScope + extendsScope + scope.functionDefinitionList
      }
      is SolFunctionDefinition -> {
        scope.parameters.asSequence() +
          (scope.returns?.parameterDefList?.asSequence() ?: emptySequence())
      }
      is SolConstructorDefinition -> {
        scope.parameterList?.parameterDefList?.asSequence() ?: emptySequence()
      }
      is SolModifierDefinition -> {
        scope.parameterList?.parameterDefList?.asSequence() ?: emptySequence()
      }
      is SolEnumDefinition -> sequenceOf(scope)
      is SolForStatement -> when {
          PsiTreeUtil.isAncestor(scope, place, false) -> {
            scope.children.firstOrNull()
              ?.let { lexicalDeclarations(visitedScopes, it, place) } ?: emptySequence()
          }
          else -> emptySequence()
      }

      is SolStatement -> {
        scope.children.asSequence()
          .map { lexicalDeclarations(visitedScopes, it, place) }
          .flatten()
      }

      is SolBlock -> scope.statementList.declaredBefore(place).flatMap { lexicalDeclarations(visitedScopes, it, place) }
      is SolUncheckedBlock -> scope.statementList.declaredBefore(place).flatMap { lexicalDeclarations(visitedScopes, it, place) }

      is SolidityFile -> {
        RecursionManager.doPreventingRecursion(scope.name, true) {
          val scopeChildren = scope.children
          val contracts = scopeChildren.asSequence()
            .filterIsInstance<SolContractDefinition>()

          val constantVariables = scopeChildren.asSequence()
            .filterIsInstance<SolConstantVariable>()

          val freeFunctions = scopeChildren.asSequence()
            .filterIsInstance<SolFunctionDefinition>()

          val userDefinedTypes = scopeChildren.asSequence()
            .filterIsInstance<SolUserDefinedValueTypeDefinition>()

          val enums = scopeChildren.asSequence()
            .filterIsInstance<SolEnumDefinition>()

          val imports = scopeChildren.asSequence().filterIsInstance<SolImportDirective>()
            .flatMap { import ->
              val declarations = import.importedFile?.let { lexicalDeclarations(visitedScopes, it, place) } ?: emptySequence()
              val selected = import.importAliasedPairList.mapNotNullTo(hashSetOf()) { it.userDefinedTypeName.name }
              if (selected.isEmpty()) declarations else declarations.filter { it.name in selected }
            }
          imports + contracts + constantVariables + freeFunctions + userDefinedTypes + enums
        } ?: emptySequence()
      }

      is SolTupleStatement -> {
        scope.variableDeclaration?.let {
          val declarationList = it.declarationList
          val typedDeclarationList = it.typedDeclarationList
          val identifier = it.identifier
          when {
            declarationList != null -> declarationList.declarationItemList.asSequence()
            typedDeclarationList != null -> typedDeclarationList.typedDeclarationItemList.asSequence()
            identifier != null -> listOf(it).asSequence()
            else -> emptySequence()
          }
        } ?: emptySequence()
      }
      is SolTryStatement -> {
        scope.parameterList?.parameterDefList?.asSequence() ?: emptySequence()
      }
      is SolCatchClause -> {
        scope.parameterList?.parameterDefList?.asSequence() ?: emptySequence()
      }

      else -> emptySequence()
    }
  }

}

/** The statements of a block visible at [place]: in a block around it, only those that start before it. */
private fun List<SolStatement>.declaredBefore(place: PsiElement): Sequence<SolStatement> {
  val offset = place.textRange.startOffset
  return asSequence().filter { it.containingFile != place.containingFile || it.textRange.startOffset < offset }
}

private fun SolNamedElement.isPrivateMember(): Boolean = when (this) {
  is SolStateVariableDeclaration -> visibility == Visibility.PRIVATE
  is SolFunctionDefinition -> visibility == Visibility.PRIVATE
  else -> false
}

/** Whether a member with this visibility can be referenced as `C.member` (a library's internal ones can be too). */
internal fun Visibility?.isExternallyReferenceable(inLibrary: Boolean) =
  this == Visibility.PUBLIC || this == Visibility.EXTERNAL || this == Visibility.INTERNAL && inLibrary

/** The elements sharing the lowest [priority] value. */
internal fun <T> Collection<T>.findBest(priority: (T) -> Int): List<T> =
  groupBy(priority).minByOrNull { it.key }?.value ?: emptyList()

private fun <T> Sequence<T>.takeWhileInclusive(pred: (T) -> Boolean): Sequence<T> {
  var shouldContinue = true
  return takeWhile {
    val result = shouldContinue
    shouldContinue = pred(it)
    result
  }
}

/** An unknown parameter type accepts anything, since the declaration couldn't be typed. */
private fun SolType.acceptsArgument(argument: SolType) = this == SolUnknown || isAssignableFrom(argument)

fun SolCallable.canBeApplied(arguments: SolFunctionCallArguments): Boolean {
  val parameterPairs = parseParameters()
  val paramMap = arguments.expressionList.firstOrNull()
  if (paramMap is SolMapExpression) {
    val callArguments = paramMap.mapExpressionClauseList.mapNotNull { Pair(it.identifier.text, it.expression?.type ?: return@mapNotNull null) }
    if (callArguments.size != parameterPairs.size) {
      return false
    }
    return parameterPairs.sortedBy { it.first }.zip(callArguments.sortedBy { it.first })
      .none { (param, argument) ->
        param.first != argument.first || !param.second.acceptsArgument(argument.second)
      }
  } else {
    val callArgumentTypes = arguments.expressionList.map { it.type }
    val parameters = parameterPairs.map { it.second }
    val varargs = parameterPairs.find { it.first == SolInternalTypeFactory.varargsId }
    if (parameters.size != callArgumentTypes.size && varargs == null)
      return false
    // extra arguments are all matched against the varargs parameter
    val expected = if (varargs != null && callArgumentTypes.size > parameters.size) {
      parameters + List(callArgumentTypes.size - parameters.size) { varargs.second }
    } else {
      parameters
    }
    return expected.zip(callArgumentTypes).none { (paramType, argumentType) -> !paramType.acceptsArgument(argumentType) }
  }
}
