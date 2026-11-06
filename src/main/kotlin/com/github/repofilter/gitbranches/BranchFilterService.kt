package com.github.repofilter.gitbranches

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsLogBranchFilter
import com.intellij.vcs.log.VcsLogFilter
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.VcsLogRootFilter
import com.intellij.vcs.log.VcsLogStructureFilter
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.filter.VcsLogFilterUiEx
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话级的仓库筛选状态。
 *
 * 实现方式：给分支面板所属的那个 Git Log 设置 ROOT_FILTER（“路径”筛选），
 * 分支面板会通过 BranchesInGitLogUiFactoryProvider 里的 filter listener
 * 自动只显示这些仓库。
 *
 * 注意：Git Log 可以有多个标签页，每个标签页有独立的 filter UI，
 * 所以这里记录当前筛选作用的那个 UI；换标签页时旧状态作废。
 */
@Service(Service.Level.PROJECT)
internal class BranchFilterService(private val project: Project) {

    private val log = Logger.getInstance(BranchFilterService::class.java)

    private var filteredRoots: Set<VirtualFile> = emptySet()

    /** 应用筛选前用户自己的“路径”筛选，重置时恢复。 */
    private var savedRootFilter: VcsLogRootFilter? = null
    private var savedStructureFilter: VcsLogStructureFilter? = null

    /** 当前筛选作用的 Log UI。 */
    private var appliedUi: VcsLogFilterUiEx? = null

    /** 防止我们自己的 setFilters 触发“筛选被外部清除”的判断。 */
    private var applying = false

    private val listeners = CopyOnWriteArrayList<(Set<VirtualFile>) -> Unit>()

    val activeRoots: Set<VirtualFile>
        get() = filteredRoots

    fun addListener(listener: (Set<VirtualFile>) -> Unit) {
        listeners += listener
    }

    /**
     * @param ui 分支树所属的 Log 筛选 UI（由调用方从树的 DataContext 解析），
     *           为空时退回主 Log。
     */
    fun applyFilter(roots: Set<VirtualFile>, ui: VcsLogFilterUiEx?) {
        if (roots.isEmpty()) return
        val filterUi = ui ?: appliedUi ?: mainFilterUi() ?: return

        if (appliedUi !== filterUi) {
            // 换了一个 Log 标签页：上一个标签页的状态与保存的筛选都作废。
            filteredRoots = emptySet()
            savedRootFilter = null
            savedStructureFilter = null
            appliedUi = filterUi
        }

        val current = filterUi.filters
        if (filteredRoots.isEmpty()) {
            savedRootFilter = current.get(VcsLogFilterCollection.ROOT_FILTER)
            savedStructureFilter = current.get(VcsLogFilterCollection.STRUCTURE_FILTER)
        }

        val kept = current.filters.filterNot { it is VcsLogRootFilter || it is VcsLogStructureFilter }
        setFilters(filterUi, kept + VcsLogFilterObject.fromRoots(roots))

        filteredRoots = roots
        notifyChanged()
    }

    fun resetFilter(ui: VcsLogFilterUiEx?) {
        // 按钮属于某个具体分支树，优先用它所属标签页的 filter UI，
        // 避免会话里残留的旧标签页状态把重置作用到别的 Log 标签页。
        val filterUi = ui ?: appliedUi ?: return
        val current = filterUi.filters

        // “显示全部项目”要真的显示全部：除了仓库（root）与路径（structure）筛选，
        // 还要清掉分支筛选。Git Log 会把筛选持久化到 workspace.xml，重启后可能恢复出
        // 一个分支筛选；只清 root 的话分支面板仍然只剩那一个分支。
        val restored = buildList {
            addAll(current.filters.filterNot {
                it is VcsLogRootFilter || it is VcsLogStructureFilter || it is VcsLogBranchFilter
            })
            savedRootFilter?.let(::add)
            savedStructureFilter?.let(::add)
        }

        filteredRoots = emptySet()
        savedRootFilter = null
        savedStructureFilter = null
        appliedUi = null
        setFilters(filterUi, restored)

        notifyChanged()
    }

    /**
     * 用户可能在 Log 工具栏里手动改掉/清掉“路径”筛选，这里让状态跟随实际筛选。
     */
    fun onLogFiltersChanged(ui: VcsLogFilterUiEx) {
        if (applying || ui !== appliedUi) return
        val currentRoots = ui.filters.get(VcsLogFilterCollection.ROOT_FILTER)?.roots?.toSet() ?: emptySet()
        if (currentRoots == filteredRoots) return

        filteredRoots = emptySet()
        savedRootFilter = null
        savedStructureFilter = null
        notifyChanged()
    }

    @Suppress("DEPRECATION")
    private fun mainFilterUi(): VcsLogFilterUiEx? =
        runCatching { VcsProjectLog.getInstance(project).mainLogUi?.filterUi }.getOrNull()

    private fun setFilters(filterUi: VcsLogFilterUiEx, filters: Collection<VcsLogFilter>) {
        applying = true
        try {
            filterUi.setFilters(VcsLogFilterObject.collection(*filters.toTypedArray()))
        }
        catch (t: Throwable) {
            log.warn("设置 Git Log 路径筛选失败", t)
        }
        finally {
            applying = false
        }
    }

    private fun notifyChanged() {
        val snapshot = filteredRoots
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }
}