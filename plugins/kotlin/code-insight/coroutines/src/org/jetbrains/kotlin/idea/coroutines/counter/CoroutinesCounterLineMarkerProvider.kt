// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.coroutines.counter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.KotlinIcons
import org.jetbrains.kotlin.psi.KtFunction

class CoroutinesCounterLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (element !is KtFunction) {
            return null
        }

        if (element.parent == null) {
            return null
        }

        val leafIdentifier = element.nameIdentifier ?: return null
        val coroutinesCount = CoroutinesSearcher.coroutinesCountFor(element) ?: return null

        val coroutinesCountText = when (coroutinesCount) {
            CoroutinesSearcher.UNKNOWN_TAG -> "some coroutines could be launched"
            CoroutinesSearcher.INFINITY_TAG -> "Infinity"
            else -> coroutinesCount.toString()
        }

        return LineMarkerInfo(leafIdentifier, leafIdentifier.textRange, KotlinIcons.ANNOTATION,
                              { coroutinesCountText },
                              null,
                              GutterIconRenderer.Alignment.CENTER, { CoroutinesSearcher.coroutinesCountFor(element).toString() })
    }
}
