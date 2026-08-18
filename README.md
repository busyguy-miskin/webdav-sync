# 轻量 WebDAV 同步下载工具（Android）

一个面向 **Android 墨水屏设备** 的轻量级 WebDAV 同步下载 App：把 WebDAV 服务器（如 AList、坚果云、Nextcloud）上的目录增量同步到手机本地存储，主打省电、省流量、界面在电子墨水屏上清晰可读。

## 功能特性

- **任务管理**：多任务新建 / 编辑 / 删除 / 启停，SAF 授权本地目录
- **远程浏览**：Depth:1 逐层浏览远程目录再选定同步范围，支持中文路径
- **增量同步**：ETag 优先、size + lastModified 兜底，未变更文件自动跳过
- **只增不删**：默认策略，绝不误删本地文件；可按任务开启「覆盖已变更文件」
- **前台服务**：dataSync 前台服务 + 通知栏进度 + 取消按钮，锁屏 / 切后台不中断
- **多任务队列**：全部同步顺序执行，单文件失败不中断整体
- **凭证加密**：EncryptedSharedPreferences（AES-GCM 256）存储密码
- **网络策略**：仅 Wi-Fi 同步、在线检查
- **同步历史**：Room 记录每次结果，历史页可查，自动清理旧日志
- **兼容性**：自研 WebDAV 客户端，适配 AList 的多 propstat / 302 重定向等行为
- **墨水屏 UI**：Jetpack Compose 全灰阶主题，无彩色依赖

## 技术栈

Kotlin · Jetpack Compose (Material 3) · Room · OkHttp · Coroutines/Flow · SAF · 手动 DI（无 Hilt）

minSdk 29（Android 10）· targetSdk 35 · 单模块 `:app`

## 构建

```bash
./gradlew assembleDebug        # 构建 APK
./gradlew test                 # 单元测试（PROPFIND 解析等）
```

## E2E 联调测试（可选）

`WebDavClientE2ETest`（JVM）与 `DeviceSyncE2ETest`（真机）针对真实 WebDAV 服务器做端到端验证。
仓库不保存任何服务器地址与凭证，**未配置时自动跳过，不影响 CI**：

```bash
# 1) 复制模板并填入你的服务器信息（该文件已被 .gitignore 排除）
cp app/e2e.properties.example app/e2e.properties

# 2) JVM 端到端测试
./gradlew test

# 3) 真机 instrumented 测试（通过 instrumentation 参数传入）
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_SERVER=http://192.168.x.x:5244/dav \
  -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_USER=admin \
  -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_PASS=your-password \
  -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_DIR=/some/small/dir
```

## 文档

- [需求背景](docs/requirements.md)
- [系统设计](docs/design.md)
- [功能规格](docs/features.md)

## 安全说明

- 密码使用 EncryptedSharedPreferences（AES-GCM 256）加密存储，不参与云备份/设备迁移
- 默认校验 TLS 证书；任务级「信任所有证书」仅建议用于内网自签名服务器
- 允许明文 HTTP（内网 WebDAV 常态），编辑页会给出警示；公网服务器请使用 HTTPS
- 备份规则已排除加密凭证与数据库（含用户名/服务器地址）

## 已知限制

- 凭证加密依赖 `androidx.security:security-crypto`（1.1.0-alpha06，无已知 CVE；该库处于 alpha 维护状态，后续可能迁移）
- 下载写入采用「临时文件 + 原子替换」策略，同步中断不会留下残缺文件

## 许可证

[MIT](LICENSE)

## 目录结构

```
app/src/main/java/com/example/webdavsync/
├── data/          # WebDavClient / Room DAO / SAF / 凭证存储 / 网络检查
├── domain/        # SyncEngine 同步引擎
├── service/       # 前台服务与任务队列
├── ui/            # Compose 界面（列表/编辑/进度/历史）+ 墨水屏主题
└── di/            # 手动依赖容器
```
