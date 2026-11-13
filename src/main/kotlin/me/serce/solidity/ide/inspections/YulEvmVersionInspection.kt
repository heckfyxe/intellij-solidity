package me.serce.solidity.ide.inspections

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import me.serce.solidity.chain.foundry.FoundryProject
import me.serce.solidity.lang.psi.SolReferenceElement
import me.serce.solidity.lang.psi.SolVisitor
import me.serce.solidity.lang.psi.SolYulFunctionCall
import me.serce.solidity.lang.resolve.EvmVersion
import me.serce.solidity.lang.resolve.YulBuiltins
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path

/**
 * Yul built-ins that the EVM version of the project doesn't have, e.g. `tstore` before Cancun. The EVM version is
 * forge's `evm_version`; while forge's config isn't known yet (or there is no Foundry project), nothing is reported.
 */
class YulEvmVersionInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    val evm by lazy(LazyThreadSafetyMode.NONE) { EvmVersion.parse(evmVersionOf(holder.file)) }
    return object : SolVisitor() {
      override fun visitYulFunctionCall(o: SolYulFunctionCall) {
        val builtin = YulBuiltins[o.firstChild.text] ?: return
        if (builtin.since == null && builtin.until == null) return
        val version = evm ?: return
        // a user-defined function can't have a built-in's name, but don't report a resolved call anyway
        if ((o as? SolReferenceElement)?.reference?.resolve() != null) return
        val reason = YulBuiltins.unavailabilityReason(builtin, version) ?: return
        holder.registerProblem(o, reason, ProblemHighlightType.GENERIC_ERROR, o.firstChild.textRangeInParent)
      }
    }
  }

  companion object {
    /** Replaces the forge lookup in tests, which can't run forge. */
    @TestOnly
    @Volatile
    var evmVersionForTests: ((PsiFile) -> String?)? = null

    /** forge's `evm_version` for [file], or null while it isn't known; never runs forge in the calling thread. */
    fun evmVersionOf(file: PsiFile): String? {
      evmVersionForTests?.let { return it(file) }
      if (ApplicationManager.getApplication().isUnitTestMode) return null
      val vFile = file.originalFile.virtualFile ?: return null
      if (vFile.fileSystem !is LocalFileSystem) return null
      val root = FoundryProject.rootOf(vFile) ?: return null
      val project = file.project
      val foundry = FoundryProject(Path.of(root.path), FoundryProject.forgePath(project))
      // inspections run under a read action: read the config in the background, then highlight again
      val cached = foundry.cachedConfig() ?: run {
        foundry.loadConfigInBackground {
          ApplicationManager.getApplication().invokeLater({
            if (file.isValid) DaemonCodeAnalyzer.getInstance(project).restart(file)
          }, project.disposed)
        }
        return null
      }
      return cached.getOrNull()?.evmVersion
    }
  }
}
