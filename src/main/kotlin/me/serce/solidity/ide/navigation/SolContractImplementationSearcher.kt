package me.serce.solidity.ide.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope.FilesScope
import com.intellij.psi.search.GlobalSearchScope.allScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch.SearchParameters
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.*
import me.serce.solidity.lang.SolidityFileType
import me.serce.solidity.lang.psi.SolContractDefinition
import me.serce.solidity.lang.psi.SolFunctionDefinition
import me.serce.solidity.lang.psi.elements
import me.serce.solidity.lang.resolve.function.SolFunctionResolver
import me.serce.solidity.lang.stubs.SolInheritanceIndex
import java.util.*

private const val MAX_IMPLEMENTATIONS = 250

class SolContractImplementationSearcher : QueryExecutorBase<PsiElement, SearchParameters>(true) {

  override fun processQuery(queryParameters: SearchParameters, consumer: Processor<in PsiElement>) {
    when (val element = queryParameters.element) {
      is SolContractDefinition -> {
        element.findAllImplementations().forEach { consumer.process(it) }
      }
      is SolFunctionDefinition -> {
        SolFunctionResolver.collectOverrides(element).forEach { consumer.process(it) }
      }
    }
  }
}


fun SolContractDefinition.findAllImplementations(): HashSet<SolContractDefinition> {
  return CachedValuesManager.getCachedValue(this) {
    val implementations = HashSet<SolContractDefinition>()
    val implQueue = ArrayDeque<SolContractDefinition>(MAX_IMPLEMENTATIONS)
    implQueue.add(this)
    val application = ApplicationManager.getApplication()
    val search = { application.runReadAction { findAllImplementationsInAction(implQueue, implementations) } }
    when {
      application.isDispatchThread || ProgressManager.getGlobalProgressIndicator() != null -> search()
      // the reference search needs a progress indicator, see https://github.com/intellij-solidity/intellij-solidity/issues/295
      else -> ProgressManager.getInstance().runProcess(search, EmptyProgressIndicator())
    }
    implementations.remove(this)
    CachedValueProvider.Result.create(
      implementations,
      // The result is index-backed now, so it stays cheap to recompute and we can invalidate on any PSI
      // change instead of relying on the (incomplete) set of files that currently contain implementations.
      PsiModificationTracker.MODIFICATION_COUNT
    )
  }
}

private fun findAllImplementationsInAction(
  implQueue: ArrayDeque<SolContractDefinition>,
  implementations: HashSet<SolContractDefinition>
) {
  while (implQueue.isNotEmpty() && implQueue.size < MAX_IMPLEMENTATIONS && implementations.size < MAX_IMPLEMENTATIONS) {
    val current = implQueue.poll()
    if (!implementations.add(current)) {
      continue
    }
    current.findImplementations().findAll().filterTo(implQueue) { it !in implementations }
  }
}

fun SolContractDefinition.findImplementations(): Query<SolContractDefinition> {
  val contractName = name ?: return EmptyQuery()
  val virtualFile = containingFile.virtualFile
  val resolveScope = when {
    // When the file for which we're performing search for usages is a part of the sources/libs in the current project
    virtualFile != null && resolveScope.contains(virtualFile) -> resolveScope
    // However, if the project doesn't store solidity files in its source folder, we perform a global search.
    else -> allScope(project)
  }
  val solOnlyScope = useScope
    .intersectWith(FilesScope.getScopeRestrictedByFileTypes(resolveScope, SolidityFileType)) as? GlobalSearchScope
  // Look contracts up by the name of the contract they inherit from, then keep only those whose inheritance
  // actually resolves to this contract. This replaces a project-wide reference search and keeps the line
  // markers and "go to implementation" fast regardless of project size.
  val candidates = SolInheritanceIndex.KEY.elements(contractName, project, solOnlyScope)
  return CollectionQuery(
    candidates.filter { candidate ->
      candidate.supers.any { superType -> superType.reference?.multiResolve()?.contains(this) == true }
    }
  )
}
