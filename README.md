# 33 Terminal

一个从零设计的原生 Android Ubuntu 终端应用，不依赖任何已有终端项目的代码或模块。

目标：打开应用，完成首次初始化后进入 Ubuntu 24.04 arm64 用户空间；日常操作保持简洁、稳定、低干扰。

## 当前版本 1.1.0

- Ubuntu 24.04.3 arm64 Base 用户空间
- Ubuntu Base 压缩包设计为 APK `assets` 内置资源
- 首次启动从 APK 复制、校验并解压 Ubuntu Base
- PRoot 无 root 运行
- 原生 Kotlin + Jetpack Compose UI
- 深色终端界面
- 命令输入、IME 回车、Ctrl+C
- 上下历史、Tab、Esc、方向键、Home、End 快捷键
- 一键复制输出、清空输出
- 常用命令快捷栏
- `/mnt/shared` 映射 Android 共享存储
- C.UTF-8、xterm-256color、独立 HOME/TMP 环境
- 只构建 arm64-v8a
- Release 开启 R8 与资源压缩
- 不创建、不使用 GitHub Actions

## 必需资源

仓库构建前需要把 Canonical 官方 Ubuntu Base 24.04.3 arm64 压缩包放到：

```text
app/src/main/assets/ubuntu-base-24.04.3-base-arm64.tar.gz
```

本次使用的压缩包 SHA-256：

```text
7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048
```

压缩包约 29 MB。构建时不会再把 Ubuntu Base 作为普通网络下载项；运行时首次启动直接从 APK 读取，然后复制到应用私有目录并校验、解压。

## PRoot

为了控制仓库二进制体积，当前版本仍会在首次启动时获取 arm64 Android PRoot runtime，并缓存到应用私有目录。Ubuntu Base 本身不需要下载。

## 终端行为

当前运行模型使用 PRoot + `/bin/bash --login`。输入区负责发送命令，辅助按键负责发送常用控制字符。输出采用 UTF-8，并保留最近一段输出，适合移动设备长期使用。

## 构建

使用 Android Studio 打开仓库即可构建。建议环境：

- JDK 17+
- Android SDK 35
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21

选择 `app` 模块构建 Release：

```text
app/build/outputs/apk/release/app-release.apk
```

## 设备

当前只面向 `arm64-v8a`。首次启动需要解压 Ubuntu Base；当前 PRoot runtime 仍需要网络获取。

## 体积目标

Ubuntu Base 官方发布压缩包约 29 MB。应用只包含 arm64-v8a，并启用 R8/资源压缩；目标 APK 小于 100 MB。最终 APK 大小必须以实际 Release 构建结果为准。

## 项目说明

这是一个独立的 `33` 项目：UI、初始化流程、Ubuntu 解包逻辑和运行管理均独立实现，没有引用其他终端项目。

## License

GPLv3
