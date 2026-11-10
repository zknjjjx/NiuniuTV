# 妞妞影视 2（电视版）

遥控器操作的家庭影院 App：豆瓣做门面（海报、简介、演员、推荐），片源来自 [zknjjjx/tvbox](https://github.com/zknjjjx/tvbox) 的 `cmstv.json`（多条加速线路，安装包内置快照兜底）。点播放自动测速挑最快的源，起播 12 秒没画面或卡顿 20 秒自动换下一个源。

- 界面：`web/`（改完跑 `./build-web.sh` 编译到 assets，兼容 Android 7 的旧 WebView）
- 原生：`app/src/main/java/com/niuniu/tv/`（WebView 外壳、跨域请求、测速、Media3 播放器）
- 构建：推送 `v2` 分支自动出 APK，见 Releases 的 `v2-latest`

## 3.0：TVBox 接口与许可

3.0 起可在设置里选一个 TVBox 接口（预设饭太硬等热门接口，也可自定义），接口里的 jar / JS / Python 爬虫源和采集站一起搜索、测速。官源只用接口自己配置的解析，App 不内置解析。

爬虫运行部分（`com.github.catvod.*`、QuickJS 模块、Python 加载）移植自 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)，该项目以 AGPL-3.0 发布。因此本项目整体按 AGPL-3.0 发布，许可全文见 [LICENSE](LICENSE)，源代码即本仓库。
