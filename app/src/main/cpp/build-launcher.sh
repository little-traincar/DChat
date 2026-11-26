#!/bin/sh
# 构建独立 Python 子进程启动器（prebuilt 放在 app/src/main/jniLibs/arm64-v8a/libpychild.so）。
# 需要 ANDROID_NDK_HOME 指向 NDK（r26/r27 均可），在项目根目录执行：
#   sh app/src/main/cpp/build-launcher.sh
set -eu
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
HOST_TAG=windows-x86_64
case "$(uname -s)" in
  Linux*) HOST_TAG=linux-x86_64 ;;
  Darwin*) HOST_TAG=darwin-x86_64 ;;
esac
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"
OUT="$(dirname "$0")/../../jniLibs/arm64-v8a/libpychild.so"
mkdir -p "$(dirname "$OUT")"
"$CC" -O2 -fPIE -pie -o "$OUT" "$(dirname "$0")/pychild.c" -ldl
echo "built $OUT"
