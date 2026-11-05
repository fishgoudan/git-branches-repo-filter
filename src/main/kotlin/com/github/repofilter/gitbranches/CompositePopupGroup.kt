package com.github.repofilter.gitbranches

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware

/**
 * 把内置的分支树菜单组和本插件动作组合在一起。
 *
 * 注意：内置组的 getChildren 需要在后台线程执行（BranchActionsBuilder 标注了
 * @RequiresBackgroundThread），所以这里只做纯组合，不读取任何 Swing 状态。
 */
internal class CompositePopupGroup(
    private val builtin: ActionGroup,
    private val extra: AnAction,
) : ActionGroup(), DumbAware {

    init {
        templatePresentation.isPopupGroup = true
        templatePresentation.isHideGroupIfEmpty = true
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val builtinChildren = runCatching { builtin.getChildren(e) }.getOrDefault(AnAction.EMPTY_ARRAY)
        return if (builtinChildren.isEmpty()) {
            arrayOf(extra)
        }
        else {
            arrayOf(*builtinChildren, Separator.getInstance(), extra)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}