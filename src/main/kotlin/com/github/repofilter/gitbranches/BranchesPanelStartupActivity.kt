package com.github.repofilter.gitbranches

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class BranchesPanelStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDefault) return
        BranchesPanelInstaller(project).start()
    }
}