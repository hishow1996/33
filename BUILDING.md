# 构建指南

## 环境要求
- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.2+

## 首次构建
```bash
# 1. 下载模型资产（大文件不入库，约 360MB）
./scripts/download_models.sh

# 2. 获取 sherpa-onnx aar（约 50MB，不入库）
mkdir -p app/libs
curl -fL --retry 3 -o app/libs/sherpa-onnx.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar

# 3. 配置本机 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 4. 编译未签名 APK（无需把签名密钥放进仓库）
./gradlew assembleRelease
```

## 可选：配置本机 Release 签名

在仓库根目录创建不会提交到 Git 的 `signing.properties`：

```properties
storeFile=/本机路径/your-release-key.jks
storePassword=替换为本机密钥库密码
keyAlias=替换为本机别名
keyPassword=替换为本机密钥密码
```

也可以使用环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。环境变量优先于 properties 文件。请勿将密钥库、密码或签名配置提交到 Git。没有签名配置时，`assembleRelease` 生成未签名 APK；需要安装测试时可使用 Android Studio 的 debug 构建，或配置自己的签名。

## 架构
- Kotlin + Compose M3 + Room + media3(ExoPlayer) + NewPipe Extractor + sherpa-onnx + ML Kit
- 详见各源码包：`app/src/main/java/io/translatetube/local/`
