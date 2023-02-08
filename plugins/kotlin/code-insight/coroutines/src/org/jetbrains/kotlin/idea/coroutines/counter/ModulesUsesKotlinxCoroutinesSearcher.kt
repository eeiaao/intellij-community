// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.coroutines.counter

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.libraries.Library
import com.intellij.util.Processor

class ModulesUsesKotlinxCoroutinesSearcher(private val project: Project) {
    private val KOTLINX_COROUTINES_LIB_NAME = "kotlinx-coroutines"
    private val KOTLINX_COROUTINES_PACKAGE_NAME = "jetbrains.kotlinx.coroutines"

    private fun isKotlinxCoroutineLibrary(library: Library): Boolean {
        val libraryName = library.name ?: return false

        return libraryName.contains(KOTLINX_COROUTINES_LIB_NAME) ||
                libraryName.contains(KOTLINX_COROUTINES_PACKAGE_NAME)
    }

    fun getModulesUsesCoroutines(): ArrayList<Module> {
        val modulesUsesCoroutines = arrayListOf<Module>()
        val projectModules = ModuleManager.getInstance(project).modules

        projectModules.forEach { module ->
            val libraryVisitor = Processor<Library> { lib ->
                if (isKotlinxCoroutineLibrary(lib)) {
                    modulesUsesCoroutines.add(module)
                }
                true
            }

            ModuleRootManager.getInstance(module).orderEntries().forEachLibrary(libraryVisitor)
        }

        return modulesUsesCoroutines
    }
}
