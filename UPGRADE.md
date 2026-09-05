# 33 Terminal — 1.3.0

## 已完成

- 增量 ANSI/VT 控制序列归一化基础设施
- 移动端 Ctrl、方向键、PageUp/PageDown 序列集中定义
- 终端字体大小/自动跟随偏好持久化
- Ubuntu rootfs 健康检查
- 快捷命令扩展：磁盘与内存检查
- 终端主题 token 与运行参数集中管理
- Ubuntu/shared 路径集中管理

## 下一阶段

- terminal cell/grid 状态机与真实 SGR 彩色渲染
- 光标、清屏、滚动区域、alternate screen
- 多 PTY session
- Android SAF 文件桥接
- PRoot runtime 内置 APK 并校验
- 增量 UI buffer / 环形历史
- 键盘、横竖屏与 PTY window size 同步

所有工作保持 arm64-v8a、无 root、无 GitHub Actions，并以 APK 小于 100 MB 为硬约束。
