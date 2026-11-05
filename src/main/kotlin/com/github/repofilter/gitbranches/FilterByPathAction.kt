package com.github.repofilter.gitbranches

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import javax.swing.JTree

/**
 * 分支树仓库节点右键菜单项：筛选当前路径。
 */
internal class FilterByPathAction(
    private val project: Project,
    private val tree: JTree,
) : AnAction(), DumbAware {

    init {
        templatePresentation.text = ACTION_TEXT
        templatePresentation.description = "只显示所选仓库的分支与提交"
        templatePresentation.icon = AllIcons.General.Filter
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val roots = GitUiBridge.selectedRepositoryRoots(tree)
        val available = roots.isNotEmpty()
        e.presentation.isVisible = available
        e.presentation.isEnabled = available
        e.presentation.text = if (roots.size > 1) "筛选所选 ${roots.size} 个路径" else ACTION_TEXT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val roots = GitUiBridge.selectedRepositoryRoots(tree)
        if (roots.isEmpty()) return
        project.service<BranchFilterService>().applyFilter(roots.toSet(), GitUiBridge.filterUi(tree))
    }

    private companion object {
        const val ACTION_TEXT = "筛选当前路径"
    }
}