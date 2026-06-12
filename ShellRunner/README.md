# RouXin ShellToolBox

Android 终端脚本工具箱 — 文件管理 + 多标签真 PTY 终端，需要 Root 权限。

## 下载

| 版本 | APK | 说明 |
|------|-----|------|
| **3.7 版本（多进程）** | [ShellRunner-v3.7.3-multi-process.apk](./releases/ShellRunner-v3.7.3-multi-process.apk) | Termux PTY + 多标签页 + 会话持久化 |
| **3.6 版本（单进程）** | [ShellRunner-v3.6.3-single-process.apk](./releases/ShellRunner-v3.6.3-single-process.apk) | 轻量版，包名 `com.Rouxin.ShellTool` |

> 两个版本包名不同，支持在同一设备同时安装。
> 备用下载：[123云盘](https://www.123pan.cn/s/TSHCTd-gHJJh)

## 功能

- 脚本管理：按扩展名自动选择解释器（sh/py/rb/pl/lua）
- 文件浏览：ls -la + Java API 双模式，目录缓存
- 多标签真 PTY 终端（Termux 终端引擎 + NDK PTY）
- 完整 ANSI 彩色输出（16 / 256 色）
- 终端配色方案：暗色、亮色、绿莹、琥珀、日光、Monokai
- 字体大小可调 / 双指缩放
- 长按文本选择 → 复制粘贴
- 无 Root 降级模式（文件浏览可用）
- 深色/浅色主题切换

## 架构

```
ShellRunner/              # 多进程版（前台 Service + PTY 终端）
ShellRunner-single/       # 单进程版（纯 Activity，无 Service）
releases/                 # 构建产物 APK
```

### 多进程版（3.7 版本）

```
TerminalActivity (companion object 持久化 sessions)
  ├── PTY TerminalSession × N  (liveSessions 保活)
  ├── TerminalView (Termux)
  └── ForegroundService 保活进程
```

- 会话跨 Activity 销毁保活（companion object）
- `singleTask` + `onNewIntent` 支持新脚本追加 Tab
- NDK PTY：posix_openpt + fork + exec su
- 多标签页 + 输出自适应滚动

### 单进程版（3.6 版本）

```
TerminalActivity
  └── PTY TerminalSession (Activity 内联)
```

- 轻量，页面退出会话结束
- 与多进程版相同的 Termux 终端引擎

## 构建

```bash
# 多进程版
cd ShellRunner && ./gradlew assembleRelease

# 单进程版
cd ShellRunner-single && ./gradlew assembleRelease
```

- NDK 版本：26.1
- 目标架构：arm64-v8a
- 最低 API：28（Android 9）

## 许可证

本项目为个人开源项目，仅供参考学习。
