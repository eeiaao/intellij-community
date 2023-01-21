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

        private val mainCache = mutableMapOf<PsiElement, Int>()

        fun coroutinesCountFor(f: PsiElement): Int? {
            return mainCache[f]
        }
    }

    fun visit(psiFile: PsiFile) {
        val cache = mutableMapOf<PsiElement, Int>()

        fun increaseCountForFunction(function: PsiElement, addend: Int) {
            if (addend < 0) {
                throw InvalidArgument()
            }

            val knownCount = cache.getOrDefault(function, 0)
            val sum = knownCount + addend

            if (sum < knownCount) {
                cache[function] = INFINITY_TAG
                return
            }

            cache[function] = sum
        }

        val nestedCallsCache = mutableMapOf<PsiElement, MutableList<PsiElement>>()

        val functions = project.runReadActionInSmartMode { psiFile.collectDescendantsOfType<KtNamedFunction>() }

        functions.forEach { function ->
            indicator.checkCanceled()

            val nestedCalls = project.runReadActionInSmartMode { function.collectDescendantsOfType<KtCallExpression>() }

            if (nestedCalls.isEmpty()) {
                nestedCallsCache[function] = mutableListOf()
                return@forEach
            }

            nestedCalls.forEach { callExpression ->
                indicator.checkCanceled()

                val callReference = callExpression.referenceExpression() ?: return
                val isCoroutineLaunch = project.runReadActionInSmartMode { callReference.isCoroutineLaunch() }

                if (isCoroutineLaunch) {
                    increaseCountForFunction(function, 1)
                } else {
                    val callReferenceAsPsi = callReference as PsiElement
                    val seen = callReferenceAsPsi.getUserData(SEEN_KEY)

                    if (seen == null) {
                        val resolvedPsiElement = project.runReadActionInSmartMode {
                            callReferenceAsPsi.reference?.resolve()
                        }

                        val cachedResolved = CachedValue(project, true) {
                            CachedValueProvider.Result.create(resolvedPsiElement, callReferenceAsPsi)
                        }

                        val cachedSeen = CachedValue(project) {
                            CachedValueProvider.Result.create(true, ModificationTracker.NEVER_CHANGED)
                        }

                        callReferenceAsPsi.putUserData(SEEN_KEY, cachedSeen)
                        callReferenceAsPsi.putUserData(RESOLVED_KEY, cachedResolved)
                    }

                    val cachedResolved = callReferenceAsPsi.getUserData(RESOLVED_KEY)
                    val resolvedPsiElement = project.runReadActionInSmartMode { cachedResolved?.value } ?: return

                    val nestedCalls = nestedCallsCache.getOrDefault(function, mutableListOf())
                    nestedCalls.add(resolvedPsiElement)
                    nestedCallsCache[function] = nestedCalls
                }
            }
        }

        val independent = cache.keys.subtract(nestedCallsCache.keys)
        independent.forEach {
            nestedCallsCache[it] = mutableListOf()
        }

        try {
            resolveFunctionsOrder(nestedCallsCache)

            nestedCallsCache.forEach { (function, calledFunctions) ->
                calledFunctions.forEach { declaration ->
                    val addend = cache[declaration] ?: return@forEach
                    increaseCountForFunction(function, addend)
                }
            }
        } catch (err: CircularDependencyError) {
            cache.keys.forEach { function ->
                cache[function] = UNKNOWN_TAG
            }
        }

        nestedCallsCache.clear()

        cache.forEach { entry ->
            mainCache[entry.key] = entry.value
        }
    }

    private fun resolveFunctionsOrder(cache: MutableMap<PsiElement, MutableList<PsiElement>>) {
        indicator.checkCanceled()

        val ordered = mutableSetOf<PsiElement>()
        val nestedCallsCacheCopy = cache.toMutableMap()

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
