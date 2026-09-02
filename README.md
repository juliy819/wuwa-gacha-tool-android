# Wuwa Gacha Tool Android

《鸣潮》抽卡记录的 Android 展示与导入客户端。支持云鸣潮、官方唤取链接、本地 Room 持久化、资源包缓存，以及与桌面端使用同一 `sync/v1` 协议的 OneDrive 双向同步。

## 构建

项目要求 JDK 17 与 Android SDK 35：

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

项目已内置公开的 OneDrive Client ID，普通用户无需额外配置即可使用同步。首次同步会在 OneDrive 根目录自动创建 `Wuwa Gacha Tool` 文件夹，并保存共享数据库 `gacha-data.db`。需要替换应用时，构建时可通过 Gradle 属性或环境变量覆盖 Client ID：

```powershell
$env:WUWA_ONEDRIVE_CLIENT_ID = '<client-id>'
./gradlew.bat assembleDebug
```

应用不包含客户端密钥，仅申请 `offline_access` 与 `Files.ReadWrite`。刷新令牌经 Android Keystore AES-GCM 加密后保存，访问令牌只保留在内存中。未配置 Client ID 时其余功能仍可构建和使用，云同步入口会显示不可用原因。

同步使用 SQLite 数据库快照，启动时后台检查云端版本，下载后校验并事务替换共享数据表；本机与云端同时修改时会停止并提示，避免覆盖。删除、清空和模拟记录修改会随整库快照同步。协议与边界详见 [docs/cloud-sync-v1.md](docs/cloud-sync-v1.md)。
