# 33 Terminal 1.3.0

基础升级已完成，保持现有 Ubuntu bootstrap + Native PTY 路径不变。

## 已加入
- 增量 ANSI/VT 控制序列处理基础
- 常用移动端 Ctrl/方向键/PageUp/PageDown 序列
- 终端显示偏好持久化
- Ubuntu rootfs 健康检查
- 快捷诊断命令
- 集中的主题、路径、运行参数和显示常量
- 版本与 ABI 标识

## 下一阶段
- cell/grid 终端状态机与 SGR 彩色渲染
- 光标、alternate screen、清屏和滚动区域
- 多 PTY session
- SAF 文件桥接
- PRoot runtime APK 内置与校验
- 增量/环形输出缓冲
- 键盘、横竖屏与 PTY window size 同步

约束：arm64-v8a、无 root、无 GitHub Actions、APK < 100 MB。
