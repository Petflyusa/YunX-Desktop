# macOS 版

> macOS 版与 Windows 版共用同一套代码：解析、下载、登录、视频等核心功能一致，
> 只有系统集成部分按平台分别实现（见下方差异表）。

## 安装

1. 到本仓库的 **Releases**（或 Actions 里最近一次「macOS DMG 构建」的产物）下载对应芯片的 dmg：
   - Apple Silicon（M1 / M2 / M3 / M4…）：`YunX-Desktop-<版本>-macos-arm64.dmg`
   - Intel：`YunX-Desktop-<版本>-macos-x64.dmg`
2. 打开 dmg，把 `YunX-Desktop.app` 拖进「应用程序」。
3. 应用没有 Apple 签名，**第一次打开**请在 Finder 里对它 **右键 →「打开」→「打开」**；
   或在终端执行：

```bash
xattr -dr com.apple.quarantine /Applications/YunX-Desktop.app
```

## 与 Windows 版的差异

| 功能 | Windows | macOS |
| --- | --- | --- |
| 下载进度提示 | 通知中心 toast 进度条 | Dock 图标进度条 + 完成时系统通知 |
| 托盘 | 任务栏托盘 + 原生右键菜单 | 菜单栏图标 + 原生菜单；点 Dock 图标可唤回隐藏的窗口 |
| 凭证密钥「较为严格」档 | Windows DPAPI | macOS 钥匙串 |
| 一键导入浏览器 Cookie | Chrome / Edge / 360 / QQ / Firefox 等（DPAPI 解密） | Chrome / Edge / Brave / Vivaldi / Opera / Arc / Firefox（钥匙串解密，首次会弹授权框；Safari 不支持） |
| FFplay 预览 | 嵌入应用窗口 | 独立窗口播放（也可在「引擎管理」改用内置 JavaFX 播放器） |
| FFmpeg 引擎下载源 | gyan.dev 精简版 | ffmpeg.martin-riedl.de 静态构建；也会自动识别 Homebrew 安装的 FFmpeg |
| Gopeed 内核 | `windows-amd64` 包 | `macos-arm64` / `macos-amd64` 包 |
| 主窗口位置选择 | C# 透明遮罩 | 半透明可拖动框 |
| 自动更新 | 上游 Release（exe / zip） | 只认带 `.dmg` 的 Release；上游只有 Windows 包时不弹更新 |
| 下载完成后关机 | `shutdown` | 通过「系统事件」关机（首次需授权「自动化」） |

数据目录与 Windows 版一致：`~/.yunx-desktop`（可用环境变量 `YUNX_DESKTOP_DATA_DIR` 覆盖）。

## 已知问题

- 目前只在一台 Apple Silicon（M4）机器上实际运行验证过；Intel 版的打包配置已写好，但还没有真机测试。
- 出现过一次退出时崩溃（崩溃栈在 `NSMenu performKeyEquivalent`，疑似由 Cmd+Q 触发，尚未稳定复现）。
  崩溃后下次启动会把内嵌浏览器的数据目录隔离并重建。
  在修复前，建议用菜单栏图标 →「退出」或直接关闭窗口来退出。

## 构建

macOS 安装包必须在 macOS 上打。仓库自带 GitHub Actions 工作流
[`.github/workflows/macos-dmg.yml`](../.github/workflows/macos-dmg.yml)：

- 推送到 `main` 或在 Actions 页手动运行 → 产出 arm64 / x64 两个 dmg（Actions artifact）；
- 推送 `v*` 标签 → 把两个 dmg 附加到该标签对应的 Release（Release 不存在时会新建一个）。

本机有 Mac 时也可以直接构建（需要 JDK 17）：

```bash
java -jar gradle/wrapper/gradle-wrapper.jar :desktop:packageDmg
```

产物在 `desktop/build/compose/binaries/main/dmg/`。
