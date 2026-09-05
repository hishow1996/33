# Changelog

## 1.5.0
- Improved VT alternate-screen handling for full-screen terminal programs such as vim/nano-style applications.
- Preserved the normal cursor position across DEC alternate-screen mode 1049.
- Added detection state for DEC bracketed paste mode (CSI ? 2004 h/l), ready for UI paste routing.
- Bumped Android version to 1.5.0.
- Kept arm64-v8a only, no root, no GitHub Actions, and release shrinking enabled.

## 1.4.0
- Added a VT-style terminal screen buffer foundation.
- Added lazy multi-session management (up to four sessions).
- Added SAF file import bridge for the shared directory.
- Bumped Android version to 1.4.0.
- Kept arm64-v8a only, no root, no GitHub Actions, and release shrinking enabled.

## 1.3.0
- Added terminal preferences and health checks.
- Added shortcut definitions and upgrade documentation.
