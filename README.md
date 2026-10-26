# PC Deck

Turn an Android tablet into a **Stream Deck–style controller** for your Windows PC over your home Wi-Fi. Use the PC Deck Android app, or any browser.

- **Deck**: big customizable buttons across multiple pages. Each button can run a shortcut, media/volume control, launch an app or file, open a website, type text, run a command, run a multi-step macro, or jump to another page.
- **Edit on the tablet**: tap ✏️ to add, edit, recolor, reorder, or delete buttons and pages. Changes save to the PC right away.
- **Touchpad & keyboard**: trackpad with tap-to-click, two-finger right-click and scroll, drag-lock, plus a text box and special keys.
- **Screen view**: see your PC screen live. Tap to click, long-press to right-click.
- **Android app**: install PC Deck on your tablet, scan the QR code once, and it reconnects automatically every time you open it. Full-screen, keeps the screen awake, with haptic feedback.

## Setup (Windows)

1. Install [Python 3.10+](https://www.python.org/downloads/) (tick **Add python.exe to PATH**).
2. Download this repo and double-click **`start.bat`**. The first run installs what it needs.
3. The window shows a link and a QR code. Open it on your tablet (same Wi-Fi).
4. On the tablet, use the browser menu → **Add to Home screen** so it opens full-screen like an app.

If Windows Firewall asks, allow access on **Private networks**.

## Android app

1. On your tablet, open this repo's **Releases** page and download **PC-Deck.apk** from *Latest Android build*.
2. Open the file and allow installing from your browser if Android asks.
3. Start `start.bat` on the PC, open PC Deck on the tablet, and tap **Scan QR code**.

The app remembers your PC. Tap 🔌 (top-right) or press Back to switch PCs.

GitHub builds a new APK automatically whenever the `android/` folder changes (Actions → *Build Android app*). By default each build is signed with a temporary key, so you may need to uninstall before installing a newer build. To avoid that, add a signing key as repository secrets: `PCDECK_KEYSTORE_BASE64`, `PCDECK_KEYSTORE_PASSWORD`, `PCDECK_KEY_ALIAS`, `PCDECK_KEY_PASSWORD`.

You can also use PC Deck without the app by opening the link from `start.bat` in any browser.

## Button actions

| Action | Example |
|---|---|
| Keyboard shortcut | `ctrl+shift+m`, `win+d`, `alt+f4`, `f5` |
| Media / volume | play_pause, next, prev, mute, vol_up, vol_down |
| Open app or file | `notepad.exe`, `C:\Program Files\OBS Studio\bin\64bit\obs64.exe`, a `.lnk` shortcut |
| Open website | `https://youtube.com` |
| Type text | any text |
| Run command | `shutdown /s /t 60` |
| Multi-action | a JSON list of the above, each with an optional `"delay"` in seconds |
| Go to page | switch to another page (like a Stream Deck folder) |

Example macro:

```json
[
  { "type": "launch", "path": "notepad.exe", "delay": 0.8 },
  { "type": "text", "text": "Hello from PC Deck" },
  { "type": "hotkey", "keys": "ctrl+s" }
]
```

Your layout lives in `server/config.json` (created on first run from `config.default.json`).

## Security

The link contains a private token, and the server rejects anything without it. Anyone with that link can control your PC, so only use it on your own network and don't share it. To reset the token, delete the `"token"` line from `server/config.json` and restart. Do not port-forward this to the internet.

## Run manually

```bash
cd server
pip install -r requirements.txt
python deck_server.py
```
