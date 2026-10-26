"""PC Deck server - turns a tablet into a Stream Deck style controller for this PC.

Run:  python deck_server.py
Then open the printed URL on your tablet (same Wi-Fi).
"""
import asyncio
import hmac
import io
import json
import os
import secrets
import shlex
import socket
import subprocess
import sys
import time
import webbrowser
from pathlib import Path

# Make screen coordinates physical pixels on Windows (fixes scaling mismatch)
if sys.platform == "win32":
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        pass

from aiohttp import web, WSMsgType
from pynput.keyboard import Controller as KeyController, Key, KeyCode
from pynput.mouse import Controller as MouseController, Button

try:
    import mss
    from PIL import Image
    HAVE_SCREEN = True
except ImportError:
    HAVE_SCREEN = False

ROOT = Path(__file__).resolve().parent
WEB_DIR = ROOT.parent / "web"
CONFIG_PATH = ROOT / "config.json"
DEFAULT_CONFIG_PATH = ROOT / "config.default.json"

keyboard = KeyController()
mouse = MouseController()

# ---------------------------------------------------------------- config

def load_config():
    if not CONFIG_PATH.exists():
        cfg = json.loads(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
        cfg["token"] = secrets.token_urlsafe(16)
        save_config(cfg)
        return cfg
    cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(16)
        save_config(cfg)
    return cfg


def save_config(cfg):
    tmp = CONFIG_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    tmp.replace(CONFIG_PATH)


CONFIG = load_config()


def authorized(request):
    token = request.query.get("t") or request.headers.get("X-Deck-Token", "")
    return hmac.compare_digest(token.encode(), CONFIG["token"].encode())

# ---------------------------------------------------------------- keys

KEY_ALIASES = {
    "ctrl": Key.ctrl, "control": Key.ctrl, "shift": Key.shift, "alt": Key.alt,
    "win": Key.cmd, "cmd": Key.cmd, "super": Key.cmd, "meta": Key.cmd,
    "enter": Key.enter, "return": Key.enter, "esc": Key.esc, "escape": Key.esc,
    "tab": Key.tab, "space": Key.space, "backspace": Key.backspace,
    "delete": Key.delete, "del": Key.delete, "insert": Key.insert,
    "home": Key.home, "end": Key.end, "pageup": Key.page_up, "pagedown": Key.page_down,
    "up": Key.up, "down": Key.down, "left": Key.left, "right": Key.right,
    "printscreen": Key.print_screen, "prtsc": Key.print_screen,
    "capslock": Key.caps_lock,
    "playpause": Key.media_play_pause, "next": Key.media_next,
    "prev": Key.media_previous, "previous": Key.media_previous,
    "mute": Key.media_volume_mute, "volup": Key.media_volume_up,
    "voldown": Key.media_volume_down,
}
for i in range(1, 25):
    if hasattr(Key, f"f{i}"):
        KEY_ALIASES[f"f{i}"] = getattr(Key, f"f{i}")


def parse_key(name):
    name = name.strip().lower()
    if name in KEY_ALIASES:
        return KEY_ALIASES[name]
    if len(name) == 1:
        return KeyCode.from_char(name)
    raise ValueError(f"Unknown key: {name}")


def press_hotkey(combo):
    keys = [parse_key(k) for k in combo.replace(" ", "").split("+") if k]
    for k in keys:
        keyboard.press(k)
    for k in reversed(keys):
        keyboard.release(k)


def tap(key):
    keyboard.press(key)
    keyboard.release(key)

# ---------------------------------------------------------------- actions

MEDIA = {
    "play_pause": Key.media_play_pause, "next": Key.media_next,
    "prev": Key.media_previous, "mute": Key.media_volume_mute,
    "vol_up": Key.media_volume_up, "vol_down": Key.media_volume_down,
}


async def run_action(action):
    kind = action.get("type")
    if kind == "hotkey":
        press_hotkey(action.get("keys", ""))
    elif kind == "text":
        keyboard.type(action.get("text", ""))
    elif kind == "media":
        key = MEDIA.get(action.get("key"))
        if key is None:
            raise ValueError("Unknown media key")
        for _ in range(max(1, int(action.get("repeat", 1)))):
            tap(key)
    elif kind == "launch":
        target = action.get("path", "").strip()
        args = action.get("args", "").strip()
        if not target:
            raise ValueError("No path set")
        if sys.platform == "win32" and not args:
            os.startfile(target)  # works for .exe, .lnk, folders, files
        else:
            subprocess.Popen([target, *shlex.split(args)], close_fds=True)
    elif kind == "url":
        webbrowser.open(action.get("url", ""))
    elif kind == "command":
        subprocess.Popen(action.get("command", ""), shell=True)
    elif kind == "multi":
        for step in action.get("steps", []):
            await run_action(step)
            await asyncio.sleep(max(0, float(step.get("delay", 0.1))))
    elif kind in ("page", "none", None):
        pass  # handled on the tablet
    else:
        raise ValueError(f"Unknown action type: {kind}")

# ---------------------------------------------------------------- screen

class ScreenStreamer:
    def __init__(self, ws):
        self.ws = ws
        self.task = None
        self.fps = 8
        self.quality = 55
        self.max_width = 1280
        self.monitor = 1

    def start(self, fps=None, quality=None, max_width=None):
        if fps: self.fps = max(1, min(30, int(fps)))
        if quality: self.quality = max(20, min(90, int(quality)))
        if max_width: self.max_width = max(480, min(3840, int(max_width)))
        if self.task is None or self.task.done():
            self.task = asyncio.create_task(self._loop())

    def stop(self):
        if self.task:
            self.task.cancel()
            self.task = None

    def geometry(self):
        with mss.mss() as sct:
            mons = sct.monitors
            return mons[min(self.monitor, len(mons) - 1)]

    def _grab(self):
        with mss.mss() as sct:
            mon = sct.monitors[min(self.monitor, len(sct.monitors) - 1)]
            shot = sct.grab(mon)
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            if img.width > self.max_width:
                h = round(img.height * self.max_width / img.width)
                img = img.resize((self.max_width, h), Image.BILINEAR)
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=self.quality)
            return buf.getvalue()

    async def _loop(self):
        loop = asyncio.get_running_loop()
        try:
            while not self.ws.closed:
                t0 = time.perf_counter()
                frame = await loop.run_in_executor(None, self._grab)
                await self.ws.send_bytes(frame)
                await asyncio.sleep(max(0, 1 / self.fps - (time.perf_counter() - t0)))
        except (asyncio.CancelledError, ConnectionResetError):
            pass

# ---------------------------------------------------------------- web

BUTTONS = {"left": Button.left, "right": Button.right, "middle": Button.middle}


async def handle_ws(request):
    if not authorized(request):
        return web.Response(status=401, text="Bad token")
    ws = web.WebSocketResponse(heartbeat=20, max_msg_size=4 * 1024 * 1024)
    await ws.prepare(request)
    streamer = ScreenStreamer(ws) if HAVE_SCREEN else None
    await ws.send_json({"type": "hello", "screen": HAVE_SCREEN})
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                m = json.loads(msg.data)
                t = m.get("type")
                if t == "action":
                    await run_action(m.get("action", {}))
                    if m.get("id"):
                        await ws.send_json({"type": "ok", "id": m["id"]})
                elif t == "move":
                    mouse.move(float(m["dx"]), float(m["dy"]))
                elif t == "click":
                    mouse.click(BUTTONS.get(m.get("button"), Button.left), int(m.get("count", 1)))
                elif t == "down":
                    mouse.press(BUTTONS.get(m.get("button"), Button.left))
                elif t == "up":
                    mouse.release(BUTTONS.get(m.get("button"), Button.left))
                elif t == "scroll":
                    mouse.scroll(float(m.get("dx", 0)), float(m.get("dy", 0)))
                elif t == "key":
                    press_hotkey(m.get("keys", ""))
                elif t == "type":
                    keyboard.type(m.get("text", ""))
                elif t == "abs" and streamer:
                    g = streamer.geometry()
                    mouse.position = (g["left"] + float(m["x"]) * g["width"],
                                      g["top"] + float(m["y"]) * g["height"])
                    if m.get("click"):
                        mouse.click(BUTTONS.get(m.get("button"), Button.left), int(m.get("count", 1)))
                elif t == "screen" and streamer:
                    if m.get("on"):
                        streamer.start(m.get("fps"), m.get("quality"), m.get("width"))
                    else:
                        streamer.stop()
            except Exception as e:  # report but keep the socket alive
                await ws.send_json({"type": "error", "message": str(e), "id": m.get("id") if isinstance(m, dict) else None})
    finally:
        if streamer:
            streamer.stop()
    return ws


async def get_config(request):
    if not authorized(request):
        return web.Response(status=401)
    return web.json_response({k: v for k, v in CONFIG.items() if k != "token"})


async def put_config(request):
    if not authorized(request):
        return web.Response(status=401)
    data = await request.json()
    if not isinstance(data.get("pages"), list):
        return web.Response(status=400, text="pages must be a list")
    CONFIG["pages"] = data["pages"]
    CONFIG["columns"] = max(2, min(10, int(data.get("columns", CONFIG.get("columns", 5)))))
    save_config(CONFIG)
    return web.json_response({"ok": True})


async def index(request):
    return web.FileResponse(WEB_DIR / "index.html", headers={"Cache-Control": "no-cache"})


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    port = int(CONFIG.get("port", 8765))
    app = web.Application()
    app.add_routes([
        web.get("/", index),
        web.get("/ws", handle_ws),
        web.get("/api/config", get_config),
        web.put("/api/config", put_config),
        web.static("/static", WEB_DIR),
    ])
    url = f"http://{lan_ip()}:{port}/?t={CONFIG['token']}"
    print("\n  PC Deck is running.")
    print(f"  Open this on your tablet:\n\n    {url}\n")
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.print_ascii(invert=True)
    except ImportError:
        pass
    if not HAVE_SCREEN:
        print("  (Screen view disabled: pip install mss pillow)")
    web.run_app(app, host="0.0.0.0", port=port, print=None)


if __name__ == "__main__":
    main()
