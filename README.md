# DChat

原生 Android 的 DeepSeek 客户端。**没有 WebView、没有 H5**，所有界面由 Jetpack Compose 原生绘制。

> 当前版本：**1.0.7**（`versionCode 9`）
> 设计文档：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)（架构总览）、[`docs/PLAN.md`](docs/PLAN.md)（决策记录 D1–D91）

## 特性

- **对话**：流式输出、深度思考、联网搜索、文件解析、图片理解、语音识别与语音合成
- **工具系统**
  - 内置原生工具：文件读写（`fs.*`）、后台任务（`bg.*`）、定时任务（`schedule.*`）、下载（`local.download`）、图像生成（`local.imagegen`）
  - **用户工具**：在 App 内写 Python（Chaquopy），一个工具一个目录，独立 `sandbox/`、`site-packages/`、按会话 `state/`
  - UI Schema v2：工具可以声明页面视图（`ui.json`），由宿主静态渲染
- **权限与安全**
  - 每个会话独立工作区，严格按会话隔离
  - 三档会话权限：仅可查看 / 工作区内修改 / 完全权限（完全权限免逐次审批）
  - 危险操作审批；工具连续失败自动熔断（60s 冷却 → 5min 退避）
  - 网络默认强制 HTTPS
- **文件管理**
  - 「当前会话的文件」页：查看 / 删除 / 单文件导出
  - `export/` 虚拟根：模型写文件直接落手机「下载/DChat」（MediaStore）
  - 会话 ZIP 备份导入 / 导出
- **图像生成**
  - 通道：豆包 Seedream（默认）/ 阿里千问 / 任意 OpenAI 兼容端点
  - 参数：prompt、参考图（图生图）、provider、model、比例、张数（1–4）、图内文字、seed
  - 生成图落会话工作区，气泡内嵌显示、可全屏缩放、可左右滑动、可保存到手机
- **缓存与用量**
  - 请求前缀稳定化（对齐 DeepSeek Harness 的序列化规则），长会话前缀缓存命中 92%+
  - 用量统计：按天 / 按会话，含缓存命中率与费用估算
- **体验**：明暗主题、自定义背景与头像、四种语言（中文 / English / 日本語 / 한국어）

## 技术栈

| 项 | 值 |
| --- | --- |
| 语言 / UI | Kotlin 2.0.21 + Jetpack Compose（Material3） |
| 构建 | AGP 8.7.3 / Gradle 8.9 / JDK 17 |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 26 |
| ABI | arm64-v8a |
| 内嵌 Python | Chaquopy 16.1.0（CPython 3.11），另有独立子进程模式 |
| 模型接口 | DeepSeek `/anthropic/v1/messages`（SSE）、Files API、OCR/ASR/TTS |

## 构建

前置：

- JDK 17
- Android SDK（platform 35、build-tools 35.0.0）
- Python **3.11**（Chaquopy 构建需要，`build.gradle.kts` 里 `buildPython("python")`）

```bash
# 指向本机 SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

说明：

- `local.properties`、`*.apk`、`*.log`、设备验收截图（`ui/`）均不入库
- Release 使用默认 debug 签名，正式发布请自行配置 `signingConfig`
- 首次运行会解包 CPython stdlib 到 App 私有目录（约 18MB）

## 目录结构

```
app/src/main/java/com/dchat/app/
├─ data/            会话状态、持久化、用量统计、ZIP 备份
│  ├─ api/          请求构造、SSE 解析、缓存前缀、图片编码
│  └─ tools/        工具注册表、原生工具、Python 运行时与沙箱
├─ python/          dchat_runner.py（沙箱子进程入口）
└─ ui/              chat / settings / tools / tasks / usage / files / voice / lang
docs/               ARCHITECTURE.md、PLAN.md、tasks/
```

## 数据与隐私

- 会话、工具文件、生成图片全部保存在 App 私有目录（`/data/data/com.dchat.app/files/`）
- API Key 由用户在设置里填写，存本机 `SharedPreferences`（明文），**不随仓库分发**
- 默认禁止明文 HTTP（仅自定义本地生图端点允许 `localhost / 127.0.0.1 / 10.0.2.2`）

## 已知限制

- 仅打包 `arm64-v8a`
- 停止生成不会真正中断已发出的 HTTP 请求（结果会被丢弃、不落盘、不出卡）
- Python 沙箱为约定级（子进程与 App 同 UID）
- 未实现：会话 fork / 事件溯源、上下文压缩、工具导入导出

## 许可

尚未指定开源许可。如需复用代码，请先联系作者。
