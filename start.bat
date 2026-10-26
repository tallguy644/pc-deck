@echo off
cd /d "%~dp0server"
if not exist .venv (
  echo Setting up PC Deck for the first time...
  python -m venv .venv
  .venv\Scripts\python -m pip install --upgrade pip >nul
  .venv\Scripts\python -m pip install -r requirements.txt
)
.venv\Scripts\python deck_server.py
pause
