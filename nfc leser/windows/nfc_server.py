"""
NFC-Leser Server fuer Windows
Empfaengt NFC-Daten vom Android-Handy und verarbeitet sie.
Einfach starten - dann IP-Adresse in die Android-App eingeben.
"""

import tkinter as tk
from tkinter import ttk, scrolledtext
import threading
import json
import webbrowser
import socket
import sys
import os
import winsound
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime


# ──────────────────────────────────────────────
#  HTTP-Handler (laeuft im Hintergrund-Thread)
# ──────────────────────────────────────────────

class NFCHandler(BaseHTTPRequestHandler):
    """Empfaengt POST /nfc vom Android-Handy."""
    gui_callback = None

    def do_POST(self):
        if self.path == "/nfc":
            try:
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length)
                data = json.loads(raw.decode("utf-8"))

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"ok"}')

                if NFCHandler.gui_callback:
                    NFCHandler.gui_callback(data)
            except Exception as e:
                self.send_response(400)
                self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass  # Standard-Logging unterdrücken


# ──────────────────────────────────────────────
#  Hilfsfunktionen
# ──────────────────────────────────────────────

def get_local_ips():
    """Gibt alle lokalen IP-Adressen zurück."""
    ips = []
    # Primäre IP (empfohlene Route)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip not in ips:
            ips.append(ip)
    except Exception:
        pass

    # Alle weiteren IPs
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass

    return ips if ips else ["127.0.0.1"]


# ──────────────────────────────────────────────
#  GUI
# ──────────────────────────────────────────────

BG       = "#1e1e2e"
BG2      = "#181825"
BG3      = "#313244"
TEXT     = "#cdd6f4"
GREEN    = "#a6e3a1"
BLUE     = "#89b4fa"
YELLOW   = "#f9e2af"
RED      = "#f38ba8"
FONT     = ("Segoe UI", 10)
FONT_BIG = ("Segoe UI", 14, "bold")
FONT_LOG = ("Consolas", 9)


class NFCServerApp:
    PORT = 8765

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("NFC-Leser Server")
        self.root.geometry("640x520")
        self.root.resizable(True, True)
        self.root.configure(bg=BG)

        self.last_url: str | None = None
        self.last_content: str | None = None
        self.server: HTTPServer | None = None

        self._build_ui()
        self._start_server()

    # ── Layout ──────────────────────────────────

    def _build_ui(self):
        # Header
        hdr = tk.Frame(self.root, bg=BG, pady=12)
        hdr.pack(fill=tk.X, padx=16)

        tk.Label(hdr, text="📡  NFC-Leser Server",
                 font=FONT_BIG, bg=BG, fg=TEXT).pack()

        ips = get_local_ips()
        ip_str = "  |  ".join(ips)
        tk.Label(hdr, text=f"PC-IP:  {ip_str}",
                 font=FONT, bg=BG, fg=GREEN).pack(pady=2)
        tk.Label(hdr, text=f"Port: {self.PORT}   →   In Android-App eingeben: {ips[0]}  Port: {self.PORT}",
                 font=FONT, bg=BG, fg=BLUE).pack()

        # Trennlinie
        tk.Frame(self.root, bg=BG3, height=1).pack(fill=tk.X, padx=16)

        # Status-Zeile
        st = tk.Frame(self.root, bg=BG2, pady=6)
        st.pack(fill=tk.X, padx=16, pady=(6, 0))

        self.dot_lbl = tk.Label(st, text="●", font=("Segoe UI", 16),
                                 bg=BG2, fg=RED)
        self.dot_lbl.pack(side=tk.LEFT, padx=(8, 4))

        self.status_lbl = tk.Label(st, text="Starte Server …",
                                    font=FONT, bg=BG2, fg=TEXT)
        self.status_lbl.pack(side=tk.LEFT)

        # Letzter Scan
        last = tk.LabelFrame(self.root, text=" Letzter Scan ",
                              font=FONT, bg=BG2, fg=BLUE,
                              padx=10, pady=8)
        last.pack(fill=tk.X, padx=16, pady=8)

        self.last_var = tk.StringVar(value="Noch kein Scan empfangen …")
        tk.Label(last, textvariable=self.last_var,
                 font=("Segoe UI", 11), bg=BG2, fg=YELLOW,
                 wraplength=580, justify=tk.LEFT).pack(anchor=tk.W)

        # Buttons
        btns = tk.Frame(self.root, bg=BG, pady=4)
        btns.pack(fill=tk.X, padx=16)

        self.copy_btn = tk.Button(btns, text="Kopieren",
                                   command=self._copy_last,
                                   state=tk.DISABLED,
                                   **self._btn_style())
        self.copy_btn.pack(side=tk.LEFT, padx=(0, 6))

        self.open_btn = tk.Button(btns, text="URL öffnen",
                                   command=self._open_last_url,
                                   state=tk.DISABLED,
                                   **self._btn_style())
        self.open_btn.pack(side=tk.LEFT)

        tk.Button(btns, text="Log leeren",
                  command=self._clear_log,
                  **self._btn_style()).pack(side=tk.RIGHT)

        # Log
        log_frm = tk.LabelFrame(self.root, text=" Scan-Verlauf ",
                                 font=FONT, bg=BG2, fg=BLUE,
                                 padx=6, pady=6)
        log_frm.pack(fill=tk.BOTH, expand=True, padx=16, pady=(0, 12))

        self.log = scrolledtext.ScrolledText(
            log_frm, font=FONT_LOG,
            bg="#11111b", fg=TEXT, insertbackground=TEXT,
            state=tk.DISABLED, relief=tk.FLAT, wrap=tk.WORD)
        self.log.pack(fill=tk.BOTH, expand=True)

    def _btn_style(self):
        return dict(bg=BG3, fg=TEXT, relief=tk.FLAT,
                    activebackground="#45475a", activeforeground=TEXT,
                    padx=12, pady=4, cursor="hand2")

    # ── Server ──────────────────────────────────

    def _start_server(self):
        NFCHandler.gui_callback = self._on_nfc_received
        try:
            self.server = HTTPServer(("0.0.0.0", self.PORT), NFCHandler)
            t = threading.Thread(target=self.server.serve_forever, daemon=True)
            t.start()
            self.dot_lbl.config(fg=GREEN)
            self.status_lbl.config(text=f"Server läuft  –  wartet auf Scans …")
        except OSError as e:
            self.status_lbl.config(text=f"Fehler: Port {self.PORT} belegt? ({e})")

    # ── NFC-Daten verarbeiten ────────────────────

    def _on_nfc_received(self, data: dict):
        """Wird vom HTTP-Thread aufgerufen → weiter an GUI-Thread."""
        self.root.after(0, self._process, data)

    def _process(self, data: dict):
        ts    = datetime.now().strftime("%H:%M:%S")
        uid   = data.get("uid", "?")
        dtype = data.get("type", "TAG")
        recs  = data.get("records", [])

        lines = [f"[{ts}]  UID: {uid}   Typ: {dtype}"]

        self.last_url = None
        self.last_content = uid  # Fallback

        if recs:
            for i, rec in enumerate(recs, 1):
                rtype   = rec.get("type", "OTHER")
                content = rec.get("content", "")
                lines.append(f"  Record {i}: [{rtype}]  {content}")

                if rtype == "URL":
                    self.last_url = content
                    self.last_content = content
                    self.last_var.set(f"🌐  URL:  {content}")
                    webbrowser.open(content)          # automatisch öffnen
                elif rtype == "TEXT":
                    self.last_content = content
                    self.last_var.set(f"📝  Text:  {content}")
                    self._clip(content)               # in Zwischenablage
                elif rtype == "MIME":
                    mime = rec.get("mime", "")
                    self.last_content = content
                    self.last_var.set(f"📄  MIME ({mime}):  {content}")
        else:
            self.last_var.set(f"🔖  Tag-UID:  {uid}")

        # Buttons aktivieren
        self.copy_btn.config(state=tk.NORMAL)
        self.open_btn.config(
            state=tk.NORMAL if self.last_url else tk.DISABLED)

        # Dot blinken
        self.dot_lbl.config(fg=YELLOW)
        self.root.after(600, lambda: self.dot_lbl.config(fg=GREEN))

        # Status
        self.status_lbl.config(
            text=f"Letzter Scan: {ts}  –  UID {uid}")

        # Signalton
        try:
            winsound.Beep(880, 150)
        except Exception:
            pass

        # Log-Eintrag
        self._log_append("\n".join(lines) + "\n" + "─" * 50 + "\n")

    # ── Hilfsmethoden ───────────────────────────

    def _clip(self, text: str):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.root.update()

    def _copy_last(self):
        if self.last_content:
            self._clip(self.last_content)

    def _open_last_url(self):
        if self.last_url:
            webbrowser.open(self.last_url)

    def _clear_log(self):
        self.log.config(state=tk.NORMAL)
        self.log.delete("1.0", tk.END)
        self.log.config(state=tk.DISABLED)

    def _log_append(self, text: str):
        self.log.config(state=tk.NORMAL)
        self.log.insert(tk.END, text)
        self.log.see(tk.END)
        self.log.config(state=tk.DISABLED)

    def on_close(self):
        if self.server:
            self.server.shutdown()
        self.root.destroy()


# ──────────────────────────────────────────────

def main():
    root = tk.Tk()
    app  = NFCServerApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    root.mainloop()


if __name__ == "__main__":
    main()
