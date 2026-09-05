# 33 Terminal

原生 Android Ubuntu 终端。目标是：打开应用即可进入 Ubuntu 24.04 arm64，界面克制、响应稳定，不依赖 AI 或第三方终端项目。

## 当前版本 1.3.0

- Ubuntu 24.04.3 arm64 Base 内置于 APK
- PRoot 无 root 运行
- Native PTY，会话具备真实终端设备
- ANSI/VT 控制序列过滤器，避免控制码污染输出
- 命令历史、Tab、Esc、方向键、Home、End、Ctrl+C
- 深色 Compose UI
- 输出自动限制长度，降低长时间运行的内存压力
- `/mnt/shared` 共享存储
- arm64-v8a only
- Release R8/资源压缩
- 不使用 GitHub Actions

## 后续重点

完整彩色终端需要把文本输出升级为 cell/grid 渲染器；完全离线启动需要将 PRoot runtime 作为 APK 内置资源。两者都应在不突破 100 MB APK 目标的前提下完成。

## 构建

JDK 17+、Android SDK 35、AGP 8.7.3、Kotlin 2.0.21、NDK/CMake。Release 输出：`app/build/outputs/apk/release/app-release.apk`。

## 资源

Ubuntu Base：`app/src/main/assets/ubuntu-base-24.04.3-base-arm64.tar.gz`，SHA-256：`7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048`。

## 说明

33 是独立项目。应用不需要 root；Ubuntu 用户空间存放于应用私有目录。首次初始化会复制、校验并解包 rootfs，并准备 PRoot runtime。

## License

GPLv3
