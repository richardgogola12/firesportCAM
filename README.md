# Firesport Cam (Android)

Aplikácia na nahrávanie videa so zvukom, s úplným manuálnym ovládaním kamery a s dvoma
textovými overlaymi, ktoré sa **vpaľujú priamo do nahraného videa**. Text overlayov
prichádza cez UDP (napr. z časomiery).

## Ako získať APK

### A) GitHub (bez inštalácie čohokoľvek)
1. Vytvor na GitHube nový repozitár (môže byť súkromný).
2. Nahraj doň celý obsah tohto priečinka (aj skrytý priečinok `.github`).
   Najjednoduchšie: na stránke repozitára „Add file → Upload files“ a pretiahni všetky súbory.
3. Otvor záložku **Actions** → workflow **Build APK** sa spustí sám (alebo klikni „Run workflow“).
4. Po cca 5–8 minútach otvor dokončený beh a dole v časti **Artifacts** stiahni `FiresportCam-apk`.
5. ZIP rozbaľ, `app-debug.apk` skopíruj do mobilu a nainštaluj (povoľ inštaláciu z neznámych zdrojov).

### B) Android Studio
Otvor priečinok v Android Studiu → počkaj na Gradle sync → **Build → Build APK(s)**.
Výsledok: `app/build/outputs/apk/debug/app-debug.apk`.

Požiadavky: Android 8.0+ (API 26).

## Funkcie

### Kamera (hlavná obrazovka)
- 🔴 nahrávanie videa so zvukom (aj tlačidlami hlasitosti)
- 🔄 prepínanie medzi **všetkými** kamerami telefónu (predná, zadné, širokouhlá…)
- 🔦 svetlo (baterka) počas nahrávania
- 🎛 panel manuálnych nastavení:
  - **Zoom** (aj štipnutím prstov)
  - **Expozícia** (EV korekcia) + zámok AE
  - **Ostrenie** – auto / ťuknutím / manuálna vzdialenosť
  - **ISO** a **Uzávierka** – manuálna expozícia (ak to kamera podporuje)
  - **Vyváženie bielej** – predvoľby + zámok WB
- mriežka tretín, stavový riadok (kamera, rozlíšenie, zoom, IP, stav UDP, posledná správa)
- všetko sa ukladá a po reštarte obnoví

### Nastavenia (⚙)
- rozlíšenie (SD / HD / Full HD / 4K), snímková frekvencia (24/25/30/50/60), bitrate
- zvuk zap/vyp, EIS stabilizácia, OIS, antiflicker 50/60 Hz, redukcia šumu, doostrenie
- orientácia (na šírku / otočené / na výšku), nevypínať obrazovku, kopírovanie do galérie
- UDP: port, kódovanie (UTF-8 / Windows-1250 / ISO-8859-2…), režim rozdelenia textu,
  oddeľovač, timeout, max. dĺžka, orezanie, multicast
- **Overlay 1 a Overlay 2** – každý zvlášť:
  prefix, sufix, predvolený text, veľkosť textu, farba textu, farba pozadia,
  krytie (priesvitnosť) pozadia, pozícia X/Y v %, písmo, tučné, obrys, okraj, zaoblenie

### Pozícia overlayov
Overlay sa dá **chytiť prstom v náhľade kamery a presunúť kamkoľvek** (aj počas nahrávania).
Pozícia sa uloží. Presne sa dá nastaviť aj posuvníkmi X/Y v nastaveniach.

### Galéria (🎞)
Zobrazuje **iba videá nahrané touto aplikáciou** (náhľad, dĺžka, dátum, veľkosť).
Dlhé podržanie: prehrať, zdieľať, uložiť do galérie telefónu, premenovať, vymazať.

### Prehrávač
- rýchlosť 0.1× / 0.25× / 0.5× / 0.75× / 1× / 1.5× / 2×
- krokovanie po snímkach ◀ ▶, skok ±1 s, čas s milisekundami
- **zoom** štipnutím (až 10×) a posun prstom, tlačidlá Zoom +/−/1:1
- slučka

## UDP protokol
Aplikácia počúva na zvolenom porte (predvolene **5000**) na všetkých sieťových rozhraniach
(Wi-Fi aj hotspot). IP adresa je v stavovom riadku a v nastaveniach → Informácie.

Výsledný text overlayu = `prefix + prijatý text + sufix`.
V prefixe/sufixe/predvolenom texte `\n` znamená nový riadok.

| Režim | Správa | Overlay 1 | Overlay 2 |
|---|---|---|---|
| Rovnaký text | `12.34` | 12.34 | 12.34 |
| Rozdeliť oddeľovačom (`;`) | `L 12.34;P 13.01` | L 12.34 | P 13.01 |
| Podľa predpony | `1:12.34` / `2:13.01` | 12.34 | 13.01 |
| Iba overlay 1 / 2 | `12.34` | podľa voľby | |

Test z počítača:
```bash
python3 tools/udp_test_sender.py 192.168.1.50          # beží stopky
echo -n "L 12.34;P 13.01" | nc -u -w0 192.168.1.50 5000
```

## Poznámky
- Overlay sa kreslí cez CameraX `OverlayEffect`, takže je v náhľade aj vo videu rovnako.
  Ak ho konkrétny telefón nepodporuje, aplikácia nahráva ďalej bez overlayu a upozorní na to.
- Videá sú v priečinku aplikácie (`Android/data/sk.firesport.cam/files/Movies`).
  Pri odinštalovaní aplikácie sa zmažú – dôležité videá ulož do galérie alebo zdieľaj.
- Pri prednej kamere môže byť text v náhľade zrkadlovo otočený (vo videu je správne alebo naopak
  podľa výrobcu) – pre firesport sa odporúča zadná kamera.
