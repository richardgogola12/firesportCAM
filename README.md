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

## Novinky vo verzii 2.8

- **RS232 cez USB OTG → UDP** – CH340, CP210x, FTDI, PL2303, CDC; všetky parametre linky (rýchlosť, bity, parita, stop bity, riadenie toku, DTR/RTS), koniec správy (CR/LF, vlastný oddeľovač, prestávka), zoznam ignorovaných textov, UDP broadcast alebo IP adresy, predpona/koniec/kódovanie. Samostatné vlákna, 250+ správ/s, test výkonu.
- **Export a import profilu** do súboru .json (aj s logom).

## Novinky vo verzii 2.7

- **Nové diaľkové ovládanie** – responzívna stránka pre mobil aj PC: veľké tlačidlo nahrávania, družstvo, verdikt, vynulovanie pokusov, stav overlayov.
- **Stopky na webe** vo formáte 12.98 – s medzičasmi, voliteľne zobrazené priamo v obraze kamery.
- **UDP príkazy z webu** – rýchle tlačidlá aj vlastný príkaz/text, uložiteľné vlastné tlačidlá, voliteľne broadcast všetkým kamerám.

## Novinky vo verzii 2.6

- **Čas aj na vedľajšej kamere:** hlavná kamera preposiela časy a príkazy z časomiery vedľajším (Kamery a OBS → Posielať čas vedľajším kamerám).

## Novinky vo verzii 2.6

- **Živý obraz Full HD 30/60 fps** – hardvérové video H.264 namiesto obrázkov: web a `/obs` (prehliadač, MSE), OBS Zdroj médií / VLC cez `http://IP:8080/stream.ts`. Automatický návrat na MJPEG.
- Nové nastavenia: druh živého obrazu, rozlíšenie až 1920 px, 30/60 fps, dátový tok.

## Novinky vo verzii 2.5

- **Verdikty pokusov:** OK (úspešný), NP (nedokončený), D (diskvalifikovaný), NA (ešte nebežali). Rýchla voľba po pokuse, UDP `VERDIKT:D`, skratky v Nastavenia → Ostatné.
- **Výsledky:** stĺpec Verdikt, poradie len z OK pokusov, družstvá, ktoré nebežali, súčet verdiktov.
- **Filtre a hľadanie** v galérii aj výsledkoch: družstvo, verdikt, deň, pokus, čas od–do, text, zoradenie.
- **🧹 Upratovanie** bez odinštalovania (dočasné súbory, staré/neúspešné videá, družstvá, nastavenia, úplné vyčistenie).
- **Stály podpisový kľúč** pre zostavenia z GitHubu – ďalšie verzie sa nainštalujú cez staré.

## Novinky vo verzii 2.4

- **Počítadlo pokusov sa dá vynulovať** – v kamere (Družstvo → 🔄), v nastaveniach Súťaže alebo UDP príkazom `RESET` / `RESET:družstvo`.
- **Číslovanie pokusov:** každý deň od 1 (predvolené), od posledného vynulovania, alebo priebežne v celej súťaži.

## Novinky vo verzii 2.3
- vymazanie času z UDP: tlačidlo v kamere, na webe a príkaz CLEAR / CLEAR:1
- automatické vyhľadanie telefónu – vysielač (ESP01) nemusí poznať IP telefónu
  (FSCAM:HELLO / FSCAM:DISCOVER), program pre ESP-01 v `tools/esp01_firesport/`,
  simulátor na PC `tools/esp_simulator.py`
- program ESP-01 rozumie správam tréningovej časomiery (T:, STOPL:, STOPP:, M:, ERROR:)
  a posiela do aplikácie „ľavý;pravý“ čas, pri štarte START a pri resete CLEAR
- `tools/casomiera/casomieraOK_kamera.ino` – časomiera doplnená o presné časy terčov
  (STOPL:/STOPP:) a obmedzenie správy ERROR na 1× za sekundu

## Novinky vo verzii 2.2
- družstvo ku každému pokusu (výber v kamere alebo príkaz TEAM:názov), číslovanie pokusov,
  názov videa podľa družstva a času
- výsledková tabuľka súťaže s poradím, opravou údajov a exportom do Excelu (CSV)
- medzičasy fáz v prehrávači s porovnaním s najlepším pokusom družstva
- viac kamier: hlavný telefón spúšťa / zastavuje ostatné, import videí z iného telefónu
- OBS: čistý obraz na adrese http://IP:8080/obs (zdroj Prehliadač)

## Novinky vo verzii 2.0
- automatické spustenie / zastavenie nahrávania príkazmi z časomiery (START / STOP / MARK)
- značky v čase videa (automaticky pri zastavení času), zoznam značiek v prehrávači
- okamžitý replay po pokuse, predstih nahrávania (buffer pred štartom)
- 90/120 fps (ak ich kamera podporuje), 3. informačný overlay so zástupnými textami, logo
- ukazovateľ hlasitosti mikrofónu, stav batérie / teploty / miesta s upozorneniami
- diaľkové ovládanie cez prehliadač (http://IP:8080), profily nastavení
- galéria podľa súťaží, hromadný výber, výrez videa, porovnanie dvoch pokusov
- nahrávanie na pozadí (experimentálne), návod v aplikácii (Nastavenia → Návod)

## Verzia na zdieľanie (podpísaná vlastným kľúčom)
- V Android Studiu: **Build → Select Build Variant…** → pri module `app` zvoľ **release**,
  potom **Build → Build APK(s)**. (Alebo v termináli `gradlew assembleRelease`.)
  Podpísané APK je v `app/build/outputs/apk/release/app-release.apk`.
- Kľúč je v súbore `firesportcam-release.jks`, heslá v `keystore.properties`.
  **Oba súbory si zálohuj a nezverejňuj** – bez nich sa nedajú vydávať aktualizácie.
  Ak projekt nahrávaš na GitHub, použi súkromný repozitár.
- Prechod z testovacej (debug) verzie na podpísanú vyžaduje odinštalovanie aplikácie.
  Pri odinštalovaní sa zmažú videá v priečinku aplikácie – dôležité videá si najprv ulož do galérie.

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

### Otáčanie
Aplikácia funguje na výšku aj na šírku a otáča sa podľa telefónu (rešpektuje zámok otáčania).
Počas nahrávania sa orientácia zamkne, aby sa video neprerušilo. Pevnú orientáciu
nastavíš v Nastavenia → Ostatné.

### Nastavenia (⚙) – rozdelené na karty
Video · Kamera · Overlay 1 · Overlay 2 · UDP · Ostatné.
Karty overlayov majú hore živý náhľad, v ktorom sa overlay dá potiahnuť prstom.

- rozlíšenie (SD / HD / Full HD / 4K), snímková frekvencia (24/25/30/50/60), bitrate
- zvuk zap/vyp, EIS stabilizácia, OIS, antiflicker 50/60 Hz, redukcia šumu, doostrenie
- orientácia (automaticky / na šírku / otočené / na výšku), nevypínať obrazovku, kopírovanie do galérie
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
