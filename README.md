# 33 Terminal

一个从零设计的原生 Android Ubuntu 终端应用，不依赖任何已有终端项目的代码或模块。

目标很简单：打开应用，完成一次首次启动准备后，直接进入一个真正的 Ubuntu 24.04 arm64 用户空间；平时使用时尽量像一个干净、可靠的终端，而不是“套壳工具”。

## 特性

- Ubuntu 24.04.4 arm64 Base 用户空间
- PRoot 无 root 运行
- 首次启动自动下载并校验 Ubuntu Base，APK 不内置约 29 MB rootfs 压缩包
- 原生 Kotlin + Jetpack Compose UI
- 低干扰深色终端界面
- 命令输入、Ctrl+C、常用命令快捷栏
- `/mnt/shared` 映射 Android 共享存储
- C.UTF-8、xterm-256color、独立 HOME/TMP 环境
- 只构建 arm64-v8a，控制安装包体积
- Release 开启 R8 与资源压缩
- 不创建、不使用 GitHub Actions

## 工作方式

应用本身只负责 Android UI、Ubuntu 初始化和进程管理。Ubuntu Base 与 PRoot 在首次启动时下载到应用私有目录，因此不会把大型 Linux 用户空间塞进 APK。

Ubuntu Base 来自 Canonical 官方 Ubuntu Base 发布目录，并在解压前进行 SHA-256 校验。当前固定使用 Ubuntu 24.04.4 arm64 Base。

## 构建

使用 Android Studio 打开仓库即可构建。环境建议：

- JDK 17+
- Android SDK 35
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21

命令行构建：

```bash
./gradlew :app:assembleRelease
```

Release APK 输出在：

```text
app/build/outputs/apk/release/app-release.apk
```

## 设备要求

当前版本只面向 `arm64-v8a`。首次启动需要网络下载 Ubuntu Base 与 PRoot；解压后的 Ubuntu 用户空间会明显大于 APK 本身。

## 体积

设计目标是 APK 本体保持在 100 MB 以内。真正的 Ubuntu 用户空间不打进 APK，而是在首次启动时下载和解压。

## 说明

这是一个全新的 `33` 项目，代码结构、UI、启动流程和 Ubuntu 初始化逻辑均独立设计；没有引用 OnecodeTerminal 项目。

## License

GPLv3
