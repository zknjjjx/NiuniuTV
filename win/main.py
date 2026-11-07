"""妞妞影视 Windows 版：本机内嵌 CineNest 后端（只监听 127.0.0.1），用 WebView2 窗口显示。
数据放 %APPDATA%\\NiuniuWin；首次运行自动填好妞妞默认片源接口，装好即用。
NN_NOGUI=1 时只起服务不开窗口（CI 截图用）。"""
import asyncio, json, os, socket, sys, threading, time, urllib.request

BASE = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(BASE, "cinenest") if os.path.isdir(os.path.join(BASE, "cinenest")) else BASE
DATA = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "NiuniuWin")
os.makedirs(DATA, exist_ok=True)
os.environ["DATA_DIR"] = DATA
os.environ["RUN_COLLECTOR"] = "0"
os.environ.setdefault("STATIC_DIR", os.path.join(SRC, "static"))
sys.path.insert(0, SRC)
PORT = int(os.environ.get("NN_PORT", "38990"))   # 固定端口，收藏与观看记录（localStorage）才不会丢
URL = f"http://127.0.0.1:{PORT}/"


# 无控制台的 exe 没有 stdout/stderr：输出写到日志，方便排查
if sys.stdout is None or sys.stderr is None:
    _f = open(os.path.join(DATA, "niuniu.log"), "a", encoding="utf-8", buffering=1)
    sys.stdout = sys.stdout or _f
    sys.stderr = sys.stderr or _f


def log(*a):
    try:
        with open(os.path.join(DATA, "niuniu.log"), "a", encoding="utf-8") as f:
            f.write(time.strftime("%m-%d %H:%M:%S ") + " ".join(map(str, a)) + "\n")
    except Exception:
        pass


def first_run():
    cfg = os.path.join(DATA, "settings.json")
    if os.path.exists(cfg):
        return
    import conf
    json.dump({"interfaces": [{"name": "妞妞默认接口", "url": conf.PUBLIC_IFACE, "on": True}]},
              open(cfg, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def ours():
    try:
        with urllib.request.urlopen(URL + "api/conf", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


def port_free():
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", PORT)); return True
    except OSError:
        return False
    finally:
        s.close()


def serve(ready):
    from aiohttp import web
    import app as cn, collector
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    async def collect():
        while True:
            try:
                await collector.main()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log("采集器出错", repr(e))
            await asyncio.sleep(30)

    async def start():
        runner = web.AppRunner(cn.app, access_log=None)
        await runner.setup()
        await web.TCPSite(runner, "127.0.0.1", PORT).start()
        loop.create_task(collect())
    try:
        loop.run_until_complete(start())
    except Exception as e:
        log("启动失败", repr(e)); ready.set(); return
    ready.set()
    loop.run_forever()


class Api:
    def __init__(self):
        # 属性用下划线开头：pywebview 会遍历 js_api 的公开属性，碰到窗口对象会在启动时出错
        self._win, self._fs = None, False

    def fullscreen(self, on):
        on = bool(on)
        if self._win and on != self._fs:
            self._fs = on
            self._win.toggle_fullscreen()


# 网页里的播放器全屏 → 整个窗口全屏
FS_JS = """
if (!window.__nnfs) { window.__nnfs = 1;
  const f = () => window.pywebview && pywebview.api && pywebview.api.fullscreen(!!(document.fullscreenElement || document.webkitFullscreenElement));
  document.addEventListener('fullscreenchange', f); document.addEventListener('webkitfullscreenchange', f); }
"""


def main():
    first_run()
    if port_free():
        ready = threading.Event()
        threading.Thread(target=serve, args=(ready,), daemon=True).start()
        ready.wait(20)
    elif not ours():
        log("端口被占用", PORT)
    for _ in range(50):
        if ours():
            break
        time.sleep(0.2)
    if os.environ.get("NN_NOGUI") == "1":
        print("serving", URL, flush=True)
        while True:
            time.sleep(3600)
    import webview
    api = Api()
    win = webview.create_window("妞妞影视", URL, width=1360, height=820, min_size=(900, 560),
                                background_color="#0b0c10", js_api=api, text_select=False)
    api._win = win
    win.events.loaded += lambda: win.evaluate_js(FS_JS)
    webview.start(gui="edgechromium", private_mode=False, storage_path=os.path.join(DATA, "webview"))
    os._exit(0)


if __name__ == "__main__":
    main()
