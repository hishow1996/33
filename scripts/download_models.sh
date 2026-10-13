#!/bin/bash
# 大象翻译模型资产下载（首次构建前执行一次）
# 用途：GitHub 有 100MB 单文件限制，模型不入库，从官方渠道拉取
set -e
cd "$(dirname "$0")/.."
A=app/src/main/assets
mkdir -p $A/tts $A/whisper
TMP=$(mktemp -d)
trap "rm -rf $TMP" EXIT

echo "==> 下载 Kokoro v1.1 int8 TTS（~140MB）"
curl -L -o $TMP/kokoro.tar.bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
tar xjf $TMP/kokoro.tar.bz2 -C $TMP
cp $TMP/kokoro-int8-multi-lang-v1_1/model.int8.onnx $A/tts/
cp $TMP/kokoro-int8-multi-lang-v1_1/voices.bin $A/tts/
cp -r $TMP/kokoro-int8-multi-lang-v1_1/espeak-ng-data $A/tts/
cp -r $TMP/kokoro-int8-multi-lang-v1_1/dict $A/tts/

echo "==> 下载 Whisper base int8 ASR（~160MB）"
curl -L -o $TMP/whisper.tar.bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
tar xjf $TMP/whisper.tar.bz2 -C $TMP
cp $TMP/sherpa-onnx-whisper-base/base-encoder.int8.onnx $A/whisper/
cp $TMP/sherpa-onnx-whisper-base/base-decoder.int8.onnx $A/whisper/

echo "==> 完成：模型就位 $A/tts 与 $A/whisper"
