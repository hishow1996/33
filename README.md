# 33 Terminal

原生 Android Ubuntu Terminal。

打开应用后直接进入 Ubuntu shell，界面保持克制、清晰、接近真实终端工具。

## 特性

- Ubuntu 24.04 arm64 用户空间
- PRoot + PTY
- Kotlin + Jetpack Compose
- 多终端 Session
- ANSI / 交互式终端支持
- Ctrl+C 与常用命令快捷键
- 深色、低干扰 UI
- 仅 arm64-v8a，控制 APK 体积
- 不使用 GitHub Actions

## 构建

要求 Android Studio、JDK 17、Android SDK 34。

```bash
git clone --recurse-submodules https://github.com/hishow1996/OnecodeTerminalCore.git terminal-core
```

将本项目与 terminal-core 按 Gradle 配置组合后使用 Android Studio 构建 Release APK。

## 体积目标

只面向 arm64-v8a，避免打包其他 ABI。Ubuntu 用户空间采用精简镜像，目标 APK 小于 100 MB。

## License

GPLv3
