package com.github.repofilter.gitbranches

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.PopupHandler
import com.intellij.vcs.log.VcsLogDataKeys
import com.intellij.vcs.log.VcsLogUi
import com.intellij.vcs.log.ui.filter.VcsLogFilterUiEx
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * 所有对 Git 插件内部实现的依赖都集中在这里。
 *
 * 2024.3 ~ 2026.2 的类名与结构均保持一致：
 *  - 树组件：git4idea.ui.branch.dashboard.BranchesTreeComponent
 *  - 节点：  git4idea.ui.branch.dashboard.BranchNodeDescriptor$Repository
 *  - 菜单：  git4idea.ui.branch.dashboard.BranchesDashboardActions$BranchesTreeActionGroup
 *
 * IDE 升级后如果其中某一处变了，只需要改这个文件。
 */
internal object GitUiBridge {

    private val LOG = Logger.getInstance(GitUiBridge::class.java)

    private const val BRANCHES_TREE_SIMPLE_NAME = "BranchesTreeComponent"
    private const val NODE_DESCRIPTOR_MARKER = "BranchNodeDescriptor"
    private const val ACTION_GROUP_CLASS_NAME =
        "git4idea.ui.branch.dashboard.BranchesDashboardActions\$BranchesTreeActionGroup"

    fun isBranchesTree(component: Any?): Boolean {
        val tree = component as? JTree ?: return false
        return tree.javaClass.name.endsWith(BRANCHES_TREE_SIMPLE_NAME)
    }

    /**
     * 分支树所属的 Git Log UI。分支树嵌在 VcsLogPanel 里，而 VcsLogPanel 会在
     * DataContext 中提供 VCS_LOG_UI，所以从树向上就能拿到正确的标签页。
     */
    fun logUi(tree: JComponent): VcsLogUi? =
        runCatching { DataManager.getInstance().getDataContext(tree).getData(VcsLogDataKeys.VCS_LOG_UI) }.getOrNull()

    fun filterUi(tree: JComponent): VcsLogFilterUiEx? =
        runCatching { logUi(tree)?.filterUi as? VcsLogFilterUiEx }.getOrNull()

    /**
     * 从树节点（可能是 BranchTreeNode、包装节点或节点描述符本身）取出 BranchNodeDescriptor。
     */
    fun nodeDescriptor(value: Any?): Any? {
        var current: Any? = value
        var guard = 0
        while (guard++ < 6) {
            val node = current ?: return null
            when {
                node is DefaultMutableTreeNode -> current = node.userObject
                node.javaClass.name.contains(NODE_DESCRIPTOR_MARKER) -> return node
                else -> return invokeNoArg(node, "getNodeDescriptor")
            }
        }
        return null
    }

    /**
     * 节点描述符 -> 仓库根目录。对仓库节点返回其 GitRepository.root，其余返回 null。
     */
    fun repositoryRoot(descriptor: Any?): VirtualFile? {
        if (descriptor == null) return null
        val repository = invokeNoArg(descriptor, "getRepository") ?: return null
        return invokeNoArg(repository, "getRoot") as? VirtualFile
    }

    fun displayName(descriptor: Any?): String? =
        if (descriptor == null) null
        else (invokeNoArg(descriptor, "getDisplayName") as? String)?.takeIf { it.isNotBlank() }

    fun selectedRepositoryRoots(tree: JTree): List<VirtualFile> {
        val paths = tree.selectionPaths ?: return emptyList()
        return paths
            .mapNotNull { repositoryRoot(nodeDescriptor(it.lastPathComponent)) }
            .distinct()
    }

    /**
     * 反射创建 Git 插件内置的分支树右键菜单组（它是内部代码 new 出来的，没有注册到 ActionManager）。
     */
    fun createBranchesTreeActionGroup(loader: ClassLoader): ActionGroup? {
        return runCatching {
            val clazz = Class.forName(ACTION_GROUP_CLASS_NAME, true, loader)
            val constructor = clazz.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance() as? ActionGroup
        }.onFailure {
            LOG.warn("无法创建 Git 分支树内置菜单组，右键菜单将只保留本插件动作", it)
        }.getOrNull()
    }

    /**
     * 用「内置菜单 + 本插件动作」替换分支树上已有的右键菜单。
     *
     * @return 是否替换成功
     */
    fun replacePopupMenu(tree: JComponent, extra: AnAction, place: String): Boolean {
        val builtin = createBranchesTreeActionGroup(tree.javaClass.classLoader)
        if (builtin == null) return false

        tree.mouseListeners
            .filterIsInstance<PopupHandler>()
            .forEach { tree.removeMouseListener(it) }

        PopupHandler.installPopupMenu(tree, CompositePopupGroup(builtin, extra), place)
        return true
    }

    private fun invokeNoArg(target: Any, methodName: String): Any? {
        return runCatching {
            target.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?.invoke(target)
        }.getOrNull()
    }
}