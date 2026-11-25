package me.serce.solidity.util

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * [path] relative to this directory, or as given when it is absolute (`/x` or `C:\x`, on any OS); absolute paths are
 * looked up in this directory's file system, or the local one when there is no directory.
 */
fun VirtualFile?.resolvePath(path: String): VirtualFile? {
  val normalized = FileUtil.toSystemIndependentName(path)
  if (OSAgnosticPathUtil.isAbsolute(normalized)) return (this?.fileSystem ?: LocalFileSystem.getInstance()).findFileByPath(normalized)
  return this?.findFileByRelativePath(normalized)
}
