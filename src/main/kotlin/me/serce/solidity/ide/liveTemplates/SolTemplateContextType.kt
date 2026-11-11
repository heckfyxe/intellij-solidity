package me.serce.solidity.ide.liveTemplates

import com.intellij.codeInsight.template.FileTypeBasedContextType
import me.serce.solidity.lang.SolidityFileType
import me.serce.solidity.lang.SolidityLanguage

class SolTemplateContextType : FileTypeBasedContextType(
  SolidityLanguage.id,
  SolidityFileType
)

