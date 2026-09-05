# 33 Terminal

原生 Android Ubuntu 终端。打开应用即可准备 Ubuntu 24.04 arm64 用户空间；界面克制、响应稳定，不依赖 AI 或第三方终端项目。

## 当前版本 1.3.0

- Ubuntu 24.04.3 arm64 Base 内置 APK
- PRoot 无 root 运行
- Native PTY，shell 获得真实终端设备
- ANSI/VT 控制序列归一化，避免控制码污染文本输出
- 移动端 Ctrl/功能键序列集中管理
- 命令历史、Tab、Esc、方向键、Home、End、Ctrl+C
- 终端显示参数持久化基础设施（字体大小、自动跟随）
- 长输出限制，降低长期运行内存压力
- `/mnt/shared` 共享存储
- arm64-v8a only
- Release 使用 R8/资源压缩
- 不使用 GitHub Actions

## 继续升级路线

下一阶段重点是把当前轻量文本输出层升级为真正的 terminal cell/grid renderer：SGR 颜色、光标、清屏、窗口尺寸变化和交互式程序行为应由终端状态机统一处理；同时将 PRoot runtime 做成可验证的本地资源，逐步实现完全离线首次启动。所有升级均以 APK 小于 100 MB 为约束。

## 构建

JDK 17+、Android SDK 35、AGP 8.7.3、Kotlin 2.0.21、NDK/CMake。Release 输出：`app/build/outputs/apk/release/app-release.apk`。

## 资源

Ubuntu Base：`app/src/main/assets/ubuntu-base-24.04.3-base-arm64.tar.gz`。
SHA-256：`7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048`。

## 说明

33 是独立项目。应用不需要 root；Ubuntu 用户空间存放于应用私有目录。首次初始化复制、校验并解包 rootfs，然后准备 PRoot runtime。

## License

GPLv3
