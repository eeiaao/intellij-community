// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.coroutines.counter

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.ProjectPostStartupActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import org.jetbrains.kotlin.idea.base.util.runReadActionInSmartMode
import java.lang.Runnable
import kotlin.math.roundToInt

fun ModuleRootManager.getKotlinSources(): ArrayList<VirtualFile> {
    val kotlinSources = arrayListOf<VirtualFile>()
    ModuleRootManager.getInstance(module).fileIndex.iterateContent(
        { file ->
            kotlinSources.add(file)
            true
        },
        { file -> file.extension == "kt" }
    )
    return kotlinSources
}

class CoroutinesCounterPostStartupActivity : ProjectPostStartupActivity {
    override suspend fun execute(project: Project) {
        val projectModules = ModuleManager.getInstance(project).modules
        val modulesUsesCoroutines = ModulesUsesKotlinxCoroutinesSearcher(project).getModulesUsesCoroutines()

        if (modulesUsesCoroutines.size == 0) {
            return
        }

        val message =
            "Hello! We noticed that your project has ${modulesUsesCoroutines.size} out of ${projectModules.size} modules that use kotlinx.coroutines. Do you want to analyze coroutine usages?"

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Kotlin Coroutines Counter Notification Group")
            .createNotification(
                message,
                NotificationType.INFORMATION
            ).apply {
                addAction(
                  NotificationAction.createSimple("YES", Runnable {
                    expire()

                    if (project.isDisposed) return@Runnable

                    runBackgroundableTask("Count coroutines", project) { indicator ->
                        countCoroutines(project, indicator, modulesUsesCoroutines)
                    }
                  })
                )

                addAction(
                  NotificationAction.createSimple("NO", Runnable {
                    expire()
                  })
                )

                notify(project)
            }
    }

    private fun countCoroutines(project: Project, indicator: ProgressIndicator, modulesUsesCoroutines: ArrayList<Module>) {
        val searcher = CoroutinesSearcher(project, indicator)

        var current = 0
        var total = modulesUsesCoroutines.size.toDouble()

        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            if (exception !is ProcessCanceledException) {
                throw exception
            }
        }

        val jobs = modulesUsesCoroutines.map { module ->
            GlobalScope.launch(exceptionHandler) {
                val kotlinSources = ModuleRootManager.getInstance(module).getKotlinSources()

                val psiFiles = project.runReadActionInSmartMode {
                    kotlinSources.mapNotNull { source ->
                        PsiManager.getInstance(project).findFile(source)
                    }
                }

                psiFiles.forEach { psiFile ->
                    searcher.visit(psiFile)
                }

                ++current
                val progress = current / total

                indicator.fraction = progress
                indicator.text = "${(progress * 100).roundToInt()}%"
            }
        }

      runBlocking {
          jobs.joinAll()
      }
    }
}