# 更新日志（ShellRunner 多进程版）

## v3.7.3（2026-06-12）

1、复制粘贴全链路打通
  - TerminalView.onDown() 返回 true，修复长按手势
  - TerminalSession 委托 mClient 处理复制/粘贴

2、终端自动滚动 + 屏幕适配
  - onTextChanged / updateTerminalSize 中调用 scrollToBottom()
  - 字符尺寸改用动态获取

3、包名独立
  - applicationId 改为 com.Rouxin.ShellTool.mp

## v3.7.2（2026-06-07）

1、移除废弃模块
  - ScriptExecutionService / ScriptSession / AnsiParser / TaskManager
  - 清理 .cxx 构建产物、旧 APK

## v3.7.1（2026-06-07）

1、修复终端配色只生效背景色
2、移除文件页 Linux 按钮

## v3.6.3（多进程部分，2026-06-07）

1、Tab 关闭按钮修复
2、会话持久化：session 移入 companion object.liveSessions
3、迁移 Termux PTY 终端引擎至多进程版
  - 21 个 Java 文件 + pty_helper.c + CMakeLists.txt
  - 重写 TerminalActivity.kt

## v3.2（2026-04-26）

1、多进程架构上线
  - ForegroundService + ScriptSession 多标签后台执行
  - 与单进程版包名独立，可同时安装
