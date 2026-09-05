# 33 Terminal Upgrade Plan

已完成基础升级：

- 移动端终端控制键集中管理
- ANSI/VT 输出归一化组件
- 终端显示偏好持久化基础设施
- Ubuntu rootfs 健康检查组件
- 快捷命令扩展
- 终端主题常量集中管理

尚需在实际 Android 构建/设备验证后继续接入的高级能力：

1. Cell/Grid 终端渲染器（彩色、光标、清屏、滚动区域）
2. PRoot runtime APK 内置与完整 SHA-256 校验
3. 多 PTY session
4. SAF 文件选择与共享目录操作
5. 软键盘适配和选择/复制手势
6. 增量输出渲染与环形缓冲
7. 横竖屏、字体大小和终端窗口尺寸同步

所有功能以 arm64-v8a 和 APK < 100 MB 为约束。
