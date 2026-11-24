package me.serce.solidity.lang.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import me.serce.solidity.ide.SolidityIcons
import me.serce.solidity.lang.SolidityLanguage
import me.serce.solidity.lang.stubs.SolErrorIndex
import me.serce.solidity.lang.stubs.SolEventIndex
import me.serce.solidity.lang.stubs.SolGotoClassIndex
import javax.swing.Icon

/** The names in [SolGotoClassIndex] (contracts, structs, enums, value types, events, errors) with their icons. */
@Service(Service.Level.PROJECT)
class SolContractNamesCache(private val project: Project) {
  // Completion asks for these names often, so caching avoids repeated getAllKeys()
  // which is relatively expensive, even though it's from an index
  private val iconsCache: CachedValue<Map<String, Icon>> = CachedValuesManager.getManager(project).createCachedValue({
    val index = StubIndex.getInstance()
    val icons = HashMap<String, Icon>()
    index.getAllKeys(SolGotoClassIndex.KEY, project).forEach { icons[it] = SolidityIcons.CONTRACT }
    // the kind-specific indexes say which of them are events and errors
    index.getAllKeys(SolEventIndex.KEY, project).forEach { icons.replace(it, SolidityIcons.EVENT) }
    index.getAllKeys(SolErrorIndex.KEY, project).forEach { icons.replace(it, SolidityIcons.ERROR) }
    CachedValueProvider.Result.create<Map<String, Icon>>(
      icons,
      ProjectRootModificationTracker.getInstance(project),
      PsiModificationTracker.getInstance(project).forLanguage(SolidityLanguage)
    )
  }, false)

  fun allNames(): Set<String> = iconsCache.value.keys

  fun iconOf(name: String): Icon = iconsCache.value[name] ?: SolidityIcons.CONTRACT

  companion object {
    fun getInstance(project: Project): SolContractNamesCache = project.service()
  }
}
