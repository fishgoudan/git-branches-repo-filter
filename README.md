# Git Branches Repository Filter

IntelliJ IDEA 插件：在 **Git 工具窗口 → Log → 左侧分支面板**（按仓库分组）中，按仓库快速筛选分支与提交。

适用于一个项目里包含多个 Git 仓库（monorepo / 多模块）的场景。仓库多了以后分支面板会非常长，右键某个仓库即可只看它的分支。

## 功能

- 右键仓库节点 → **筛选当前路径**：只显示该仓库的分支与提交。
- 按住 `Ctrl` / `Shift` 多选仓库节点 → **筛选所选 N 个路径**。
- 分支面板搜索框右侧新增 **显示全部项目** 按钮，一键清除筛选。
- 按钮状态跟随 Git Log 的真实筛选：仓库、路径、分支任一筛选生效时按钮可用。
- 若筛选前你已经在 Log 工具窗口里设置过「路径」筛选，重置时会恢复它。
- 支持多选、右键未选中节点时自动先选中。

筛选通过 Git Log 的「路径」（root）筛选实现，因此 **Log 的提交列表也会一起被过滤**（方案 A）。

注意：Git Log 会把它自己的筛选（路径、分支等）持久化到项目的 `.idea/workspace.xml`，重启 IDE 后可能被自动恢复。遇到「重启后分支面板只剩一个分支」时，点 **显示全部项目** 即可清除恢复出来的仓库/路径/分支筛选。

## 环境要求

- IntelliJ IDEA Ultimate **2026.2.x**（`IU-262`）
- 内置 **Git4Idea** 插件（Ultimate 自带）
- JDK 25（直接使用 IDE 自带的 JBR 即可）

## 构建

项目默认使用本机安装的 IDEA 作为编译平台，路径由 Gradle 属性 `localIdePath` 指定：

```powershell
$env:JAVA_HOME="D:\IntelliJ IDEA 2026.2.3\jbr"
.\gradlew.bat buildPlugin
```

如果 IDEA 不在默认路径，传入自己的安装目录：

```powershell
.\gradlew.bat buildPlugin -PlocalIdePath="C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.3"
```

产物：

```
build/distributions/git-branches-repo-filter-0.1.4.zip
```

> `build.gradle.kts` 里的 `local(localIdePath)` 会使用本机 IDE 的依赖，换机器时通过 `-PlocalIdePath=...` 覆盖即可。

## 安装

1. IDEA → `Settings` → `Plugins` → 齿轮 → **Install Plugin from Disk…**
2. 选择 `build/distributions/git-branches-repo-filter-0.1.4.zip`
3. 重启 IDE

## 使用

1. 打开一个包含多个 Git 仓库的项目。
2. 打开 **Git** 工具窗口，停留在 **Log** 标签页，左侧就是分支面板。
3. 右键某个仓库节点 → **筛选当前路径**；`Ctrl` 多选后可一次筛选多个仓库。
4. 点击搜索框右侧的 **显示全部项目** 恢复全部仓库。

## 实现原理

- 通过 `VcsLogFilterUiEx.setFilters(...)` 给 Git Log 设置 `ROOT_FILTER`
  （`VcsLogFilterObject.fromRoots(roots)`）。
- Git 插件自带的 `BranchesInGitLogUiFactoryProvider` 会监听筛选变化，并用
  `VcsLogUtil.getAllVisibleRoots(...)` 更新分支树的 `rootsToFilter`，分支面板因此只剩所选仓库。
- 分支树所属的 Log UI 通过 `DataManager.getDataContext(tree).getData(VcsLogDataKeys.VCS_LOG_UI)` 获取，
  避免多个 Log 标签页时筛错标签页；只有拿不到时才退回主 Log。
- 右键菜单：反射创建 Git 插件内置的
  `BranchesDashboardActions$BranchesTreeActionGroup`，与本插件动作组合后重新
  `PopupHandler.installPopupMenu(...)`，保留原有菜单项。
- 按钮：找到分支面板里 `BorderLayout.NORTH` 的搜索行，在其右侧插入一个 toolbar 风格的 `JButton`。
- 启动时只探测 Git 工具窗口（id = `Version Control`），**绝不遍历其他工具窗口的 `contentManager`**。
  2026.2 的 `ToolWindow.getContentManager()` 会触发 `createToolWindowContent`，提前创建其他面板
  （例如 Database Lookup、Backup and Sync History）会在存储未就绪时弹窗。

## 源码结构

| 文件 | 作用 |
| --- | --- |
| `GitUiBridge.kt` | 所有对 Git 插件内部实现的反射与 DataContext 访问，集中在这里 |
| `BranchesPanelInstaller.kt` | 监听 Git 工具窗口/内容页，找到分支树后安装右键菜单与按钮 |
| `BranchFilterService.kt` | 筛选状态，应用/重置 `ROOT_FILTER`，清除 `STRUCTURE_FILTER` / `BRANCH_FILTER`，恢复用户原有筛选 |
| `FilterByPathAction.kt` | 右键动作「筛选当前路径 / 筛选所选 N 个路径」 |
| `CompositePopupGroup.kt` | 内置菜单 + 本插件动作的组合 |
| `BranchesPanelStartupActivity.kt` | `ProjectActivity` 入口 |

## 已知限制

- Log 的提交列表会随分支面板一起被过滤（这是方案 A 的预期行为）。
- 本插件自身不在重启后恢复筛选；但 Git Log 会持久化它自己的筛选（`workspace.xml`），
  重启后可能恢复出仓库/路径/分支筛选，点 **显示全部项目** 可清除。
- 依赖 Git 插件的内部实现（`BranchesTreeComponent`、`BranchNodeDescriptor$Repository`、
  `BranchesDashboardActions$BranchesTreeActionGroup`）。IDE 大版本升级后如果其中某一处变化，
  右键菜单或节点识别可能失效；适配点集中在 `GitUiBridge.kt`。
- `plugin.xml` 目前限定 `262`（2026.2.x），升级 IDE 后需要调整 `since-build` / `until-build`。

## 排查

打开 `Help` → `Show Log in Explorer`，搜索 `GitBranchesRepoFilter`。

- 成功挂接：
  ```
  GitBranchesRepoFilter: 分支面板挂接完成 (tree=git4idea.ui.branch.dashboard.BranchesTreeComponent, popup=true, logUi=true)
  GitBranchesRepoFilter: “显示全部项目”按钮已添加
  ```
- `popup=false`：内置菜单组类名或构造方式变了，右键菜单只剩本插件动作。
- `logUi=false`：分支树不在 `VcsLogPanel` 内，筛选会退回主 Log。
- 未找到搜索行：分支面板布局变化，按钮不会添加，但右键筛选仍可用。

## License

[MIT](LICENSE)