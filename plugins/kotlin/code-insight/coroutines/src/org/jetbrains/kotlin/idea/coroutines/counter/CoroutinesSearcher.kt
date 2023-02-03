// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.coroutines.counter

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import org.jetbrains.kotlin.caches.project.CachedValue
import org.jetbrains.kotlin.idea.base.util.runReadActionInSmartMode
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression

fun KtReferenceExpression.isCoroutineLaunch(): Boolean {
    when (text) {
        "launch",
        "async",
        "runBlocking" -> return true

        else -> return false
    }
}

class CircularDependencyError : Error()
class InvalidArgument : Error("Invalid Argument")

class CoroutinesSearcher(private val project: Project, private val indicator: ProgressIndicator) {
    private val RESOLVED_KEY = Key.create<CachedValue<PsiElement>>("resolved")
    private val SEEN_KEY = Key.create<CachedValue<Boolean>>("seen")

    companion object {
        const val UNKNOWN_TAG = -1
        const val INFINITY_TAG = -2

        val mainCache = mutableMapOf<PsiElement, Int>()
        private fun increaseCountForFunction(function: PsiElement, addend: Int) {
            if (addend < 0) {
                throw InvalidArgument()
            }

            val knownCount = mainCache.getOrDefault(function, 0)

            mainCache[function] = if (addend > Int.MAX_VALUE - knownCount) INFINITY_TAG else knownCount + addend
        }

        fun coroutinesCountFor(f: PsiElement): Int? {
            return mainCache[f]
        }
    }

    fun collectDirectCoroutinesLaunches(psiFile: PsiFile) {
        val functions = project.runReadActionInSmartMode { psiFile.collectDescendantsOfType<KtNamedFunction>() }

        functions.forEach { function ->
            indicator.checkCanceled()

            if (mainCache.containsKey(function)) {
                return@forEach
            }

            val calls = project.runReadActionInSmartMode { function.collectDescendantsOfType<KtCallExpression>() }

            calls.forEach { call ->
                indicator.checkCanceled()

                val callReference = call.referenceExpression() ?: return@forEach
                val isCoroutineLaunch = project.runReadActionInSmartMode { callReference.isCoroutineLaunch() }

                if (isCoroutineLaunch) {
                    increaseCountForFunction(function, 1)
                }
            }
        }
    }

    fun collectIndirectCoroutinesLaunches(psiFile: PsiFile) {
        val nestedCallsCache = mutableMapOf<PsiElement, MutableList<PsiElement>>()

        val localFunctions = project.runReadActionInSmartMode { psiFile.collectDescendantsOfType<KtNamedFunction>() }

        localFunctions.forEach { function ->
            indicator.checkCanceled()

            val calls = project.runReadActionInSmartMode { function.collectDescendantsOfType<KtCallExpression>() }

            if (calls.isEmpty()) {
                nestedCallsCache[function] = mutableListOf()
                return@forEach
            }

            calls.forEach { call ->
                indicator.checkCanceled()

                val callReference = call.referenceExpression() ?: return@forEach
                val isCoroutineLaunch = project.runReadActionInSmartMode { callReference.isCoroutineLaunch() }

                if (!isCoroutineLaunch) {
                    val callReferenceAsPsi = callReference as PsiElement
                    val seen = callReferenceAsPsi.getUserData(SEEN_KEY)

                    if (seen == null) {
                        val cachedSeen = CachedValue(project) {
                            CachedValueProvider.Result.create(true, ModificationTracker.NEVER_CHANGED)
                        }

                        callReferenceAsPsi.putUserData(SEEN_KEY, cachedSeen)

                        val resolvedPsiElement = project.runReadActionInSmartMode {
                            callReferenceAsPsi.reference?.resolve()
                        }

                        if (!mainCache.containsKey(resolvedPsiElement)) {
                            return@forEach
                        }

                        val cachedResolved = CachedValue(project, true) {
                            CachedValueProvider.Result.create(resolvedPsiElement, callReferenceAsPsi)
                        }

                        callReferenceAsPsi.putUserData(RESOLVED_KEY, cachedResolved)
                    }

                    val cachedResolved = callReferenceAsPsi.getUserData(RESOLVED_KEY)
                    val resolvedPsiElement = project.runReadActionInSmartMode { cachedResolved?.value } ?: return@forEach

                    val nestedCalls = nestedCallsCache.getOrDefault(function, mutableListOf())
                    nestedCalls.add(resolvedPsiElement)
                    nestedCallsCache[function] = nestedCalls
                }
            }
        }

        val independent = localFunctions.subtract(nestedCallsCache.keys)
        independent.forEach {
            nestedCallsCache[it] = mutableListOf()
        }

        try {
            resolveFunctionsOrder(nestedCallsCache, localFunctions)

            nestedCallsCache.forEach { (function, calledFunctions) ->
                calledFunctions.forEach { declaration ->
                    val addend = mainCache[declaration] ?: return@forEach
                    increaseCountForFunction(function, addend)
                }
            }
        } catch (_: CircularDependencyError) {
            nestedCallsCache.forEach { (function) ->
                mainCache[function] = UNKNOWN_TAG
            }
        }

        nestedCallsCache.clear()
    }

    private fun resolveFunctionsOrder(cache: MutableMap<PsiElement, MutableList<PsiElement>>, localFunctions: List<KtNamedFunction>) {
        indicator.checkCanceled()

        val ordered = mutableSetOf<PsiElement>()
        val nestedCallsCacheCopy = cache.toMutableMap()

        nestedCallsCacheCopy.forEach { (function, calledFunctions) ->
            nestedCallsCacheCopy[function] = calledFunctions.intersect(localFunctions.toSet()).toMutableList()
        }

        while (nestedCallsCacheCopy.isNotEmpty()) {
            val ready = mutableSetOf<PsiElement>()
            nestedCallsCacheCopy.forEach { (function, calledFunctions) ->
                if (calledFunctions.isEmpty()) {
                    ready.add(function)
                }
            }

            if (ready.isEmpty()) {
                throw CircularDependencyError()
            }

            nestedCallsCacheCopy.forEach { (function, calledFunctions) ->
                val diff = calledFunctions.subtract(ready)
                nestedCallsCacheCopy[function] = diff.toMutableList()
            }

            ready.forEach {
                ordered.add(it)
                nestedCallsCacheCopy.remove(it)
            }
        }

        val resolved = mutableMapOf<PsiElement, MutableList<PsiElement>>()
        ordered.forEach {
            resolved[it] = cache.getOrDefault(it, mutableListOf())
        }

        cache.clear()
        resolved.forEach { (function, calledFunctions) ->
            cache[function] = calledFunctions
        }
    }
}
