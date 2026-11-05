package com.github.repofilter.gitbranches

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * 在 Git 工具窗口的分支面板出现时挂接右键菜单与“显示全部项目”按钮。
 *
 * 分支面板是 IDE 内部代码懒创建的，没有扩展点，所以这里监听工具窗口 / 内容页
 * 的变化，在组件树里找到 BranchesTreeComponent 后再安装。
 *
 * 重要：本类只允许操作 Git 工具窗口（id = "Version Control"）。
 * 2026.2 的 ToolWindowImpl.getContentManager() 会调用 createContentIfNeeded()，
 * 进而执行该工具窗口的 ToolWindowFactory.createToolWindowContent()。如果在启动时
 * 遍历所有工具窗口并访问 contentManager，就会把 Database Lookup、Backup and Sync
 * History 等面板提前创建出来，它们初始化时访问尚未就绪的存储并弹窗。
 */
internal class BranchesPanelInstaller(private val project: Project) {

    private val log = Logger.getInstance(BranchesPanelInstaller::class.java)
    private val disposable = Disposer.newDisposable("GitBranchesRepoFilter")
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private var contentListenerAttached = false
    private var scanScheduled = false
    private var retriesLeft = 0

    fun start() {
        Disposer.register(project, disposable)

        project.messageBus.connect(disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(toolWindow: ToolWindow) {
                if (isGitToolWindow(toolWindow)) onGitWindowShown(toolWindow)
            }

            override fun stateChanged(
                toolWindowManager: ToolWindowManager,
                toolWindow: ToolWindow,
                eventType: ToolWindowManagerEventType,
            ) {
                if (!isGitToolWindow(toolWindow)) return
                if (eventType == ToolWindowManagerEventType.ActivateToolWindow ||
                    eventType == ToolWindowManagerEventType.ShowToolWindow
                ) {
                    onGitWindowShown(toolWindow)
                }
            }
        })

        // 项目打开时布局可能已经恢复完毕（早于本活动的订阅），此时不会再有
        // toolWindowShown 事件。这里做一次“不创建内容”的探测：
        // 1. 通过 ToolWindowImpl.addContentManagerListener 先挂监听——内容尚未创建时
        //    平台会把它暂存，等内容创建时再应用，不会触发 createToolWindowContent；
        // 2. getContentManagerIfCreated() 只读取已存在的 ContentManager，同样不创建内容。
        ApplicationManager.getApplication().invokeLater(Runnable {
            if (project.isDisposed) return@Runnable
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(GIT_TOOL_WINDOW_ID)
            if (toolWindow == null) {
                log.info("GitBranchesRepoFilter: 启动探测未找到工具窗口 $GIT_TOOL_WINDOW_ID")
                return@Runnable
            }
            attachContentListener(toolWindow, createContent = false)
            val contentCreated = hasContentManager(toolWindow)
            log.info(
                "GitBranchesRepoFilter: 启动探测 active=${toolWindow.isActive}, contentCreated=$contentCreated"
            )
            if (toolWindow.isActive || contentCreated) {
                scheduleScan(toolWindow)
            }
        }, ModalityState.any())
    }

    private fun isGitToolWindow(toolWindow: ToolWindow): Boolean =
        toolWindow.id in GIT_TOOL_WINDOW_IDS

    /**
     * 只读取，不创建：ToolWindowImpl.getContentManagerIfCreated() 在内容尚未创建时返回 null。
     * 内部 API 用反射访问，失败时退化为“未创建”。
     */
    private fun hasContentManager(toolWindow: ToolWindow): Boolean = runCatching {
        toolWindow.javaClass.getMethod("getContentManagerIfCreated").invoke(toolWindow) != null
    }.getOrElse {
        log.debug("无法调用 getContentManagerIfCreated", it)
        false
    }

    private fun onGitWindowShown(toolWindow: ToolWindow) {
        ApplicationManager.getApplication().invokeLater(Runnable {
            if (project.isDisposed) return@Runnable
            attachContentListener(toolWindow)
            scheduleScan(toolWindow)
        }, ModalityState.any())
    }

    private fun attachContentListener(toolWindow: ToolWindow, createContent: Boolean = true) {
        if (contentListenerAttached) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater(Runnable {
                if (!project.isDisposed) attachContentListener(toolWindow, createContent)
            })
            return
        }

        val listener = object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                scheduleScan(toolWindow)
            }

            override fun contentAdded(event: ContentManagerEvent) {
                scheduleScan(toolWindow)
            }
        }

        if (!createContent) {
            // ToolWindowImpl.addContentManagerListener 在 ContentManager 尚未创建时
            // 只把监听器暂存起来，不会触发面板 UI 创建。
            val deferred = runCatching {
                toolWindow.javaClass
                    .getMethod("addContentManagerListener", ContentManagerListener::class.java)
                    .invoke(toolWindow, listener)
            }
            if (deferred.isSuccess) {
                contentListenerAttached = true
                return
            }
            // 拿不到内部方法且内容还没创建时，等窗口真正显示后再挂（见 toolWindowShown）。
            if (!hasContentManager(toolWindow)) {
                log.debug("无法延迟订阅 Git 工具窗口内容变化，等待窗口显示后再挂接", deferred.exceptionOrNull())
                return
            }
        }

        contentListenerAttached = true
        runCatching {
            toolWindow.contentManager.addContentManagerListener(listener)
        }.onFailure {
            contentListenerAttached = false
            log.warn("订阅 Git 工具窗口内容变化失败", it)
        }
    }

    private fun scheduleScan(toolWindow: ToolWindow) {
        if (scanScheduled) return
        scanScheduled = true
        retriesLeft = SCAN_RETRY_COUNT
        ApplicationManager.getApplication().invokeLater(Runnable { performScan(toolWindow) }, ModalityState.any())
    }

    private fun performScan(toolWindow: ToolWindow) {
        if (project.isDisposed) {
            scanScheduled = false
            return
        }

        val installed = runCatching { scanToolWindow(toolWindow) }
            .onFailure { log.warn("扫描 Git 分支面板失败", it) }
            .getOrDefault(false)

        if (installed || retriesLeft <= 0) {
            scanScheduled = false
            return
        }

        // Git Log UI 可能异步初始化，分支树晚于内容页出现，短暂重试即可。
        retriesLeft--
        alarm.addRequest({ performScan(toolWindow) }, RETRY_DELAY_MS)
    }

    /** 只扫描 Git 工具窗口当前选中的内容页，绝不访问其他工具窗口。 */
    private fun scanToolWindow(toolWindow: ToolWindow): Boolean {
        val content = toolWindow.contentManager.selectedContent ?: return false
        return scanComponent(content.component)
    }

    private fun scanComponent(component: Component): Boolean {
        if (component is JTree) {
            if (!GitUiBridge.isBranchesTree(component)) return false
            install(component)
            return true
        }
        if (component is Container) {
            for (child in component.components) {
                if (scanComponent(child)) return true
            }
        }
        return false
    }

    private fun install(tree: JTree) {
        if (tree.getClientProperty(KEY_INSTALLED) != null) return
        tree.putClientProperty(KEY_INSTALLED, true)

        val service = project.service<BranchFilterService>()

        // 必须先于右键菜单处理器注册：右键点击未选中的节点时先选中它。
        tree.addMouseListener(SelectionFixListener(tree))

        val action = FilterByPathAction(project, tree)
        val replaced = GitUiBridge.replacePopupMenu(tree, action, ActionPlaces.VCS_LOG_BRANCHES_PLACE)
        if (!replaced) {
            log.warn("未能接管 Git 分支树右键菜单，IDE 内部结构可能已变化")
        }

        installResetButton(tree, service)
        subscribeLogFilterChanges(tree, service)

        log.info("GitBranchesRepoFilter: 分支面板挂接完成 (tree=${tree.javaClass.name}, popup=$replaced, logUi=${GitUiBridge.logUi(tree) != null})")
    }

    private fun installResetButton(tree: JTree, service: BranchFilterService) {
        val host = findSearchRowHost(tree)
        if (host == null) {
            log.warn("未找到分支面板搜索行，无法添加“显示全部项目”按钮")
            return
        }
        if (host.getClientProperty(KEY_RESET_BUTTON) != null) return

        val layout = host.layout as BorderLayout
        val searchRow = layout.getLayoutComponent(BorderLayout.NORTH)
        host.putClientProperty(KEY_RESET_BUTTON, true)

        val button = JButton(RESET_TEXT).apply {
            putClientProperty("JButton.buttonType", "toolbar")
            addActionListener { service.resetFilter(GitUiBridge.filterUi(tree)) }
        }

        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchRow, BorderLayout.CENTER)
            add(button, BorderLayout.EAST)
        }

        host.remove(searchRow)
        host.add(wrapper, BorderLayout.NORTH)
        host.revalidate()
        host.repaint()

        fun update(roots: Set<VirtualFile>) {
            button.isEnabled = roots.isNotEmpty()
            button.toolTipText =
                if (roots.isEmpty()) RESET_TOOLTIP
                else "已筛选 ${roots.size} 个仓库，点击显示全部项目"
        }

        update(service.activeRoots)
        service.addListener { roots -> update(roots) }
        log.info("GitBranchesRepoFilter: “显示全部项目”按钮已添加")
    }

    private fun subscribeLogFilterChanges(tree: JTree, service: BranchFilterService) {
        val filterUi = GitUiBridge.filterUi(tree)
        if (filterUi == null) {
            log.warn("未找到分支树所属的 Git Log 筛选 UI，筛选状态将不会跟随 Log 变化")
            return
        }
        runCatching {
            filterUi.addFilterListener { service.onLogFiltersChanged(filterUi) }
        }.onFailure { log.warn("订阅 Git Log 筛选变化失败", it) }
    }

    /**
     * 分支面板结构：JPanel(BorderLayout) { NORTH = 搜索行, CENTER = ProgressStripe(树) }
     */
    private fun findSearchRowHost(tree: JTree): JComponent? {
        var current: Component? = tree
        while (current != null) {
            if (current is JComponent) {
                val layout = current.layout
                if (layout is BorderLayout) {
                    val north = layout.getLayoutComponent(BorderLayout.NORTH)
                    if (north != null && containsTextInput(north)) return current
                }
            }
            current = current.parent
        }
        return null
    }

    private fun containsTextInput(component: Component?): Boolean {
        if (component == null) return false
        if (component is JTextComponent) return true
        if (component is Container) return component.components.any { containsTextInput(it) }
        return false
    }

    private class SelectionFixListener(private val tree: JTree) : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = fixSelection(e)

        override fun mouseReleased(e: MouseEvent) = fixSelection(e)

        private fun fixSelection(e: MouseEvent) {
            if (!SwingUtilities.isRightMouseButton(e)) return
            val path = tree.getPathForLocation(e.x, e.y) ?: return
            val row = tree.getRowForPath(path)
            if (row < 0) return
            if (!tree.isRowSelected(row)) {
                tree.selectionPath = path
            }
        }
    }

    private companion object {
        const val GIT_TOOL_WINDOW_ID = "Version Control"

        /** 兼容未来把工具窗口改名为 "Git" 的情况。 */
        val GIT_TOOL_WINDOW_IDS = setOf(GIT_TOOL_WINDOW_ID, "Git")

        const val SCAN_RETRY_COUNT = 20
        const val RETRY_DELAY_MS = 300L

        const val KEY_INSTALLED = "gitBranchesRepoFilter.installed"
        const val KEY_RESET_BUTTON = "gitBranchesRepoFilter.resetButton"

        const val RESET_TEXT = "显示全部项目"
        const val RESET_TOOLTIP = "清除仓库筛选，显示全部项目"
    }
}