# 更新日志（ShellRunner 单进程版）

## v3.6.5（2026-06-15）

1、脚本执行重构
  - 放弃 stty -echo 方案（PTY 回显永久关闭不可恢复）
  - 改为交互式 shell + postDelayed(300ms) 写入 PTY
  - Phase 1（sh 执行）失败后自动 fallback 到 Phase 2（cd 执行）
  - Root 模式下不再硬编码 su，改回条件判断 useRoot

2、沙盒精简
  - 移除 linkToDataLocalTmp()，不再往 /data/local/tmp/ 写入沙盒文件
  - createSandbox() 简化，移除无用参数

3、错误不再静默
  - Phase 1：cd 'dir' 2>/dev/null → cd 'dir' && sh '脚本'
  - Phase 2：cd 'dir' 2>/dev/null && './脚本' → cd 'dir' && './脚本'
  - cd 失败时错误可见，不再被 2>/dev/null 吞掉

## v3.6.4（2026-06-12）

1、修复终端复制/粘贴无反应
  - TerminalSession.onCopyTextToClipboard / onPasteTextFromClipboard 原为空实现
  - 改为转发给 mClient，链路接通

2、终端自动滚动修复
  - onTextChanged 中调用 scrollToBottom()
  - updateTerminalSize() 中调用 scrollToBottom()
  - 字符尺寸改用 mRenderer 动态获取，废弃 dpf 固定值

3、代码质量优化
  - 删除未使用的 tabIdCounter
  - 修复 closeTab / killActiveSession 重复调用
  - updateTabBarItem 改用 tag 精确匹配
  - 清理 showOnEmpty 视图泄漏
  - activity_main.xml 废弃属性 singleLine → maxLines=1
  - build.gradle 移除无用依赖（libsu、constraintlayout、coordinatorlayout）
  - 关闭 viewBinding

## v3.6.3（2026-06-07）

1、包名简化
  - com.Rouxin.ShellTool.beta360 → com.Rouxin.ShellTool

2、移除 TaskManager 全部代码
  - TaskManager.kt / TaskAdapter.kt / TaskManagerActivity.kt 及布局 XML

## v3.6.2（2026-06-07）

1、修复首次执行脚本终端空白
  - 根因：PTY shell 未就绪时命令写入 ByteQueue，buffer 为 0×0
  - updateSize(80, 24) 立即初始化 buffer
  - view.postDelayed(300ms) 等待 PTY 就绪

2、输入法弹出遮挡终端
  - windowSoftInputMode="adjustResize" + OnGlobalLayoutListener 自动重算尺寸

## v3.6.1（2026-06-06）

1、终端引擎重构
  - 集成 Termux v0.118.3（terminal-emulator + terminal-view），21 个 Java 文件
  - 新增 NDK pty_helper.c（posix_openpt + fork + exec），libtermux-pty.so 约 34KB
  - 真 PTY 替代原来的 Runtime.exec + TextView
  - 内置 ANSI 解析（16/256 色），废弃 AnsiParser

2、无 Root 支持
  - 双模式工作目录：Root → /data/local/tmp/ShellScripts，无 Root → 内部存储
  - 移除强制 Root 对话框
  - API 30+ 跳设置页申请权限

3、终端个性化
  - 6 种配色预设（暗色、亮色、绿莹、琥珀、日光、Monokai）
  - 字体大小 SeekBar 调节，长按粘贴，双指缩放
  - XML 布局设置弹窗，修复触控偏移

4、修复集锦
  - settingsButton 事件绑定
  - mEmulator 不可见（Kotlin 调 Java protected 字段）
  - 主题切换需 recreate()
  - 多行输入缺少 singleLine

## v3.2.3（2026-04-29）

1、ANSI 转义码过滤：新增 stripAnsi()
2、修复无 Root 闪退
3、TaskManagerActivity 重写

## v3.2.2（2026-04-27）

1、CRLF 换行符修复：执行前 sed 清洗 \r
2、输出缓冲丢失：改为 su 交互式 + marker 分界符
3、文件时间显示修复

## v3.2.1（2026-04-27）

1、路径双斜杠修复
2、`..` 导航路径修复：改用 canonicalFile.parent
3、script -c fallback
4、签名配置修复

## v3.2（2026-04-26）

1、双版本架构上线（单进程 + 多进程分目录）
2、UI 重构：文件浏览器风格（参考 MT 管理器）
3、砍掉 wrapper 脚本方案，改为直接执行
4、快速导航栏

## v3.1 beta（2026-04-21）

1、砍掉 Java 模块
2、文件浏览器模式雏形
3、UI 重构开始

## v2.5.1（2026-04-21）

1、Wrapper 脚本机制成熟版本
2、中文路径支持修复

## 更早版本

- v2.4.x：持久 su + stdin 流执行、深色/浅色主题、免责声明
- v2.3.x：工作目录自动提取、全面中文化、滚动冲突修复
- v2.2.x：基础功能可用
- v2.1.6：初始稳定版本
