# 妞妞影视 2（电视版）

遥控器操作的家庭影院 App：豆瓣做门面（海报、简介、演员、推荐），片源来自 [zknjjjx/tvbox](https://github.com/zknjjjx/tvbox) 的 `cmstv.json`（多条加速线路，安装包内置快照兜底）。点播放自动测速挑最快的源，起播 12 秒没画面或卡顿 20 秒自动换下一个源。

- 界面：`web/`（改完跑 `./build-web.sh` 编译到 assets，兼容 Android 7 的旧 WebView）
- 原生：`app/src/main/java/com/niuniu/tv/`（WebView 外壳、跨域请求、测速、Media3 播放器）
- 构建：推送 `v2` 分支自动出 APK，见 Releases 的 `v2-latest`

## 下载（正式版，链接固定，永远是最新版）

- 安卓手机：https://github.com/zknjjjx/NiuniuTV/releases/download/v2-latest/NiuniuPhone.apk
- 电视 / 盒子：https://github.com/zknjjjx/NiuniuTV/releases/download/v2-latest/NiuniuTV.apk
- 国内加速（手机）：https://gh-proxy.com/https://github.com/zknjjjx/NiuniuTV/releases/download/v2-latest/NiuniuPhone.apk
- 国内加速（电视）：https://gh-proxy.com/https://github.com/zknjjjx/NiuniuTV/releases/download/v2-latest/NiuniuTV.apk
- 备用加速：把上面的 `gh-proxy.com` 换成 `ghfast.top`

安装包约 5.4 MB。提示「解析包错误」多半是文件没下完整或链接已失效（带版本号的旧链接发新版后会失效）：用上面的固定链接重新下载，并用系统浏览器下载，不要在微信、QQ 里直接打开。

## 设备要求（正式版 2.9.x，电视版与手机版相同）

| 项目 | 要求 |
|---|---|
| 系统 | 安卓 5.0 以上可安装；建议安卓 7.0 以上（界面按安卓 7 的系统 WebView 适配，5.x、6.x 需 WebView 升级过） |
| 处理器 | 不限，ARM 32 位 / 64 位、x86 都可以 |
| 内存 | 1 GB 以上可用，2 GB 更流畅 |
| 存储 | 安装包约 6 MB；播放缓存自动占剩余空间的 1/5，最多 1 GB、至少 100 MB，每次打开 App 清空 |
| 网络 | 需要联网；在线升级需允许「安装未知应用」 |
| 操作 | 电视版只用遥控器，不需要触屏；手机版触屏操作 |

不支持：iPhone / iPad、鸿蒙 NEXT（纯血鸿蒙）。华为智慧屏（鸿蒙 2/3）理论上可装，尚未实测。
