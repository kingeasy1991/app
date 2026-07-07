# NFC-Leser  —  Android + Windows

Dein Handy scannt NFC-Tags → Daten landen sofort auf deinem PC.

---

## Wie es funktioniert

```
[NFC-Tag] ──► [Android-App] ──► WiFi ──► [Windows-Server]
                                              ├─ URL?  → Browser öffnet
                                              ├─ Text? → Zwischenablage
                                              └─ UID?  → Anzeige
```

---

## Schnellstart

### 1. Windows-Server starten

```
Voraussetzung: Python 3.8+  (python.org)
```

Doppelklick auf `windows\nfc_server.py`  
**oder** in der Eingabeaufforderung:

```cmd
cd windows
python nfc_server.py
```

Das Fenster zeigt deine **PC-IP-Adresse** an (z. B. `192.168.1.42`).

---

### 2. Android-App installieren

**Voraussetzung:** Android Studio  
<https://developer.android.com/studio>

1. Android Studio öffnen → **Open** → Ordner `android\` wählen
2. Warten bis Gradle sync fertig ist
3. Handy per USB anschließen (USB-Debugging aktivieren)
4. ▶ Run drücken → APK wird gebaut und installiert

**Oder APK direkt auf Handy bauen:**  
`Build → Generate Signed Bundle / APK → APK → debug`  
→ APK liegt in `app\build\outputs\apk\debug\app-debug.apk`

---

### 3. Verbinden

1. In der Android-App oben die **IP** und den **Port** (`8765`) eingeben
2. Auf **Einstellungen speichern** tippen
3. NFC-Tag ans Handy halten → fertig!

---

## Funktionen

| Tag-Typ | Aktion am PC |
|---------|-------------|
| URL (`https://...`) | Browser öffnet die URL automatisch |
| Text | Text wird in die Zwischenablage kopiert |
| Mifare / NfcA / … | UID + Rohdaten werden angezeigt |

---

## Als .exe bauen (optional)

```cmd
cd windows
build_exe.bat
```

→ `windows\dist\NFC-Server.exe`  — kein Python nötig zum Ausführen

---

## Anforderungen

| | |
|---|---|
| **Android** | 5.0+ mit NFC |
| **Windows** | Windows 10/11 |
| **Python** | 3.8+ (nur für Server) |
| **Netzwerk** | Handy und PC im selben WLAN |

---

## Troubleshooting

**"Verbindungsfehler"** auf dem Handy  
→ Sind beide Geräte im gleichen WLAN?  
→ Firewall auf dem PC für Port 8765 erlauben:  
`Windows Defender Firewall → Eingehende Regel → Port 8765 TCP erlauben`

**NFC wird nicht erkannt**  
→ NFC in den Android-Einstellungen aktivieren  
→ Tag direkt auf die Mitte/Rückseite des Handys halten

**Port bereits belegt**  
→ In `nfc_server.py` Zeile `PORT = 8765` auf einen anderen Port ändern  
→ In der Android-App denselben Port eintragen
