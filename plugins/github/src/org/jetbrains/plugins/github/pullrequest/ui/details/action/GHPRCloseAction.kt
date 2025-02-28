// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.ui.details.action

import org.jetbrains.plugins.github.api.data.GHRepositoryPermissionLevel
import org.jetbrains.plugins.github.i18n.GithubBundle
import org.jetbrains.plugins.github.pullrequest.data.service.GHPRSecurityService
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRStateModel
import java.awt.event.ActionEvent

internal class GHPRCloseAction(stateModel: GHPRStateModel, securityService: GHPRSecurityService)
  : GHPRStateChangeAction(GithubBundle.message("pull.request.close.action"), stateModel, securityService) {

  override fun actionPerformed(e: ActionEvent?) = stateModel.submitCloseTask()

  override fun computeEnabled(): Boolean {
    return super.computeEnabled() &&
           (securityService.currentUserHasPermissionLevel(GHRepositoryPermissionLevel.TRIAGE) || stateModel.viewerDidAuthor)
  }
}