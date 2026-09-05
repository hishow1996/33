# Upgrade notes — 1.3.0

本次提交先完成不会破坏现有运行路径的基础升级：

- 增量 ANSI/VT 控制序列归一化，跨 PTY chunk 不再丢状态
- 手机端常用 Ctrl/方向键/PageUp/PageDown 序列集中定义
- 字体大小与自动跟随偏好持久化组件
- Ubuntu rootfs 健康检查组件
- 快捷命令扩展到磁盘/内存检查
- 终端主题 token 集中管理
- 统一版本与 ABI 标识

高级终端 cell/grid、真正彩色渲染、多 session、SAF 文件桥接、PRoot 内置资源等仍需要在 Android 构建和真机 PTY 验证后逐项接入，避免在未验证的情况下破坏现有 Ubuntu 启动链路。
