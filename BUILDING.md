# 构建指南

## 环境要求
- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.2+

## 首次构建
```bash
# 1. 下载模型资产（大文件不入库，约 360MB）
./scripts/download_models.sh

# 2. 获取 sherpa-onnx aar（50MB，同样不入库）
curl -L -o app/libs/sherpa-onnx.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

# 3. 配置本机 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 4. 编译
./gradlew assembleRelease
```

## 架构
- Kotlin + Compose M3 + Room + media3(ExoPlayer) + NewPipe Extractor + sherpa-onnx + ML Kit
- 详见各源码包：`app/src/main/java/io/translatetube/local/`
