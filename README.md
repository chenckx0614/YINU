# 一隅 · YINU

一隅是一款本地优先的 Android 记录应用。可以像发消息一样保存文字、图片、文件和语音，再按关键词、日期或类型搜索。

- 支持收藏、引用关联、提醒、长图导出和 ZIP 备份恢复。
- 可选的 Supabase 同步用于让同一账号的设备共享记录。配置方法见 [跨设备同步说明](supabase/README.md)。
- AI 整理和联网语音转写可使用用户自己配置的服务。

## 构建

用 Android Studio 打开仓库根目录，安装项目要求的 Android SDK，然后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 生成在 `app/build/outputs/apk/debug/`。

## 第三方代码

项目包含 NexioSchedule 的界面组件和 Backdrop 源码。对应出处与许可见 [Design System](core/designsystem/THIRD_PARTY.md) 和 [Backdrop](core/backdrop/THIRD_PARTY.md)。
