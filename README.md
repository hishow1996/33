# 33 Terminal

一个从零设计的原生 Android Ubuntu 终端应用，不依赖任何已有终端项目的代码或模块。

目标很简单：打开应用，完成一次首次启动准备后，直接进入一个真正的 Ubuntu 24.04 arm64 用户空间；平时使用时尽量像一个干净、可靠的终端，而不是“套壳工具”。

## 当前版本

- Ubuntu 24.04.4 arm64 Base 用户空间
- Ubuntu Base 压缩包作为 APK `assets` 内置资源
- 首次启动从 APK 复制、校验并解压 Ubuntu Base，不再下载 Ubuntu rootfs
- PRoot 无 root 运行
- 原生 Kotlin + Jetpack Compose UI
- 低干扰深色终端界面
- 命令输入、Ctrl+C、常用命令快捷栏
- `/mnt/shared` 映射 Android 共享存储
- C.UTF-8、xterm-256color、独立 HOME/TMP 环境
- 只构建 arm64-v8a，控制安装包体积
- Release 开启 R8 与资源压缩
- 不创建、不使用 GitHub Actions

## Ubuntu 资源

应用要求存在：

```text
app/src/main/assets/ubuntu-base-24.04.4-base-arm64.tar.gz
```

该文件对应 Canonical 官方 Ubuntu Base 24.04.4 arm64，并使用固定 SHA-256 校验值：

```text
04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2
```

构建系统会把 `assets` 中的文件原样打进 APK。应用首次启动时把压缩包复制到应用私有目录，校验成功后再解压。因此安装完成后不需要重新下载 Ubuntu Base。

## PRoot

当前实现仍会在首次启动时下载一个 arm64 Android PRoot runtime，并缓存到应用私有目录。这样可以避免在仓库中额外存放另一个二进制包；后续可以继续把 PRoot 也改成 APK 内置资源，实现完全离线首次启动。

## 构建

使用 Android Studio 打开仓库即可构建。环境建议：

- JDK 17+
- Android SDK 35
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21

在 Android Studio 中选择 `app` 模块并构建 Release。由于仓库刻意不包含 GitHub Actions，也没有提交 CI 配置。

Release APK 输出在：

```text
app/build/outputs/apk/release/app-release.apk
```

## 设备要求

当前版本只面向 `arm64-v8a`。Ubuntu Base 已内置进 APK；首次启动需要完成解压，并默认需要网络获取 PRoot runtime。

## 体积

Ubuntu Base 官方发布文件约 28 MB（压缩状态）。应用只打包 `arm64-v8a`，并启用 R8 与资源压缩，目标仍然是让最终 APK 保持在 100 MB 以内。最终体积必须以实际 Release 构建结果为准。

## 说明

这是一个全新的 `33` 项目，代码结构、UI、启动流程和 Ubuntu 初始化逻辑均独立设计；没有引用其他终端项目。

## License

GPLv3
