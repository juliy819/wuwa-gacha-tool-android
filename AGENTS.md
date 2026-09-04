# Wuwa Gacha Tool Android 开发规范

本文件适用于本仓库中的 AI 开发任务。开始工作前先阅读本文件、`README.md`、相关源码和测试；以当前代码与用户最新要求为准，不凭旧结论猜测现状。

## 1. 仓库职责

本仓库是独立的原生 Android 客户端，负责抽卡记录导入、Room 本地持久化、Compose 展示、资源缓存、OneDrive `sync/v1` 云同步和 Android Release 产物。桌面端业务、Tauri/Rust、OCR runtime 和资源包属于同级其他仓库，不得复制到本仓库或混合发布。

跨仓库协议、数据库快照或资源清单变更，必须同时检查桌面端生产方与 Android 消费方的兼容性、失败回退和版本升级顺序。

## 2. 改动范围与数据边界

1. 先执行只读检查：`git status --short`、相关提交历史、实现和测试，再决定修改方式。
2. 保留用户或其他任务已有修改，不覆盖、还原、格式化或顺带提交无关文件；只暂存本任务文件。
3. 抽卡记录必须按玩家 UID 和官方 `card_pool_type` 隔离；保留同秒多抽顺序、重复记录 occurrence、导入幂等和模拟记录语义，不跨池计算保底或统计。
4. 数据库迁移、删除、批量导入和云端覆盖必须使用现有 Room/事务 API，并在覆盖前校验快照完整性、schema 和表结构；失败时保留本地数据。
5. OneDrive 只保存共享数据库快照和必要元数据。不得记录 client secret、refresh token、access token 或完整抽卡 URL；令牌继续使用 Android Keystore 保护，日志不得泄露凭据、UID、路径和响应正文。
6. 外部网络请求必须保留超时、错误处理和协议兼容性；不得通过放宽校验来掩盖远端或缓存异常。

## 3. Android 实现约定

- 优先复用现有 Compose、ViewModel、Repository、Room DAO、同步状态和错误处理模式；不要为了局部 UI 引入新的架构层或依赖。
- 遵循 Android 设计规范：支持 edge-to-edge、动态字体、暗色主题、横竖屏和 TalkBack；避免固定高度、不可滚动内容和仅依赖颜色表达状态。
- 用户可见文案使用简洁中文；敏感输入在显示、选择、复制和错误提示中都要脱敏，但业务层继续使用原始值。
- 新增依赖前检查现有依赖与 Android SDK 是否已能完成需求，并评估 APK 体积、离线构建和许可证影响。
- 版本号由 `app/build.gradle.kts` 的 `versionName/versionCode` 管理；Release tag 必须是 `vX.Y.Z`，产物版本和 tag 不得不一致。

## 4. 提交消息

提交主题和正文遵循：

```text
type: 中文 summary

中文 detail，说明改了什么、为什么这样改，以及关键保护或边界。

验证：实际执行的检查、测试或人工验证。
```

`type` 只允许 `feat`、`fix`、`perf`、`refactor`、`docs`、`ci`、`chore`，使用小写英文；冒号后为简洁中文描述，不加句号。summary 面向用户和 Release Notes，避免只写文件名或内部实现。人工提交必须有正文，不得声称未执行的验证；自动版本提交 `chore(release): bump version to x.y.z` 是唯一例外。

提交前检查 `git diff --cached --stat`、`git diff --cached`、`git diff --cached --check` 和 `git status --short`。除非用户明确要求，不主动改写历史、打 tag、推送或发布。

## 5. Release Actions 约定

- `.github/workflows/release.yml` 同时支持手动触发和 `v*` tag push；普通分支 push 不得触发发布。
- 发布前必须校验 `vX.Y.Z`、已有 tag 和源码版本一致性，并运行 `./gradlew testDebugUnitTest assembleRelease`。
- Release Notes 从上一个版本 tag 到当前版本的提交生成，按 `feat/fix/perf/docs/refactor|chore|ci/其他` 分类；Release 使用生成的 `release-notes.md` 作为正文，并上传 `app/build/outputs/apk/release/*.apk`。
- 手动发布需要 bump 版本时，自动提交 `chore(release): bump version to x.y.z` 并推送 main/tag；tag push 发布不得重新改写版本或移动已有 tag。
- GitHub Actions 默认只授予完成发布所需的 `contents: write` 权限；脚本使用 `set -euo pipefail`，失败必须明确退出。

## 6. 验证与交付

按改动风险运行最小但充分的检查：

- 单元测试与构建：`./gradlew testDebugUnitTest assembleDebug`；Release 变更额外运行 `./gradlew testDebugUnitTest assembleRelease`。
- Android 测试：有设备时运行 `./gradlew connectedDebugAndroidTest`，并明确报告设备条件。
- 静态检查：`git diff --check`；workflow 修改同时验证 YAML 语法，必要时使用 `actionlint`。
- 仅运行实际可用的命令，报告真实结果；构建通过不等于真机、OneDrive、Keystore 或 Release 上传已验证。
