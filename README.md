# 妞妞影视 2（电视版）

遥控器操作的家庭影院 App：豆瓣做门面（海报、简介、演员、推荐），片源来自 [zknjjjx/tvbox](https://github.com/zknjjjx/tvbox) 的 `cmstv.json`（多条加速线路，安装包内置快照兜底）。点播放自动测速挑最快的源，起播 12 秒没画面或卡顿 20 秒自动换下一个源。

- 界面：`web/`（改完跑 `./build-web.sh` 编译到 assets，兼容 Android 7 的旧 WebView）
- 原生：`app/src/main/java/com/niuniu/tv/`（WebView 外壳、跨域请求、测速、Media3 播放器）
- 构建：推送 `v2` 分支自动出 APK，见 Releases 的 `v2-latest`
