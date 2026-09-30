/*
 * Firesport Cam – vysielač časov pre ESP-01 (ESP8266)
 * ===================================================
 *
 * Posiela text (časy) do aplikácie Firesport Cam cez UDP bez toho,
 * aby musel vopred poznať IP adresu telefónu:
 *
 *   1. Telefón každé 2 s ohlási v sieti:  FSCAM:HELLO:<port>:<názov>   (broadcast na port 5001)
 *   2. ESP si zapamätá IP adresy telefónov a posiela im časy priamo (unicast) – spoľahlivé.
 *   3. Kým žiadny telefón nepozná, pošle  FSCAM:DISCOVER  a čas posiela broadcastom
 *      do celej siete – funguje aj bez ohlásenia.
 *   Funguje aj s viacerými telefónmi naraz (viac kamier).
 *
 * Wi-Fi: knižnica WiFiManager. Ak ESP nevie pripojiť k známej sieti,
 * vytvorí vlastnú Wi-Fi „FiresportCam-ESP“ – pripoj sa na ňu mobilom,
 * otvorí sa stránka, kde vyberieš sieť a zadáš heslo. Uloží sa natrvalo.
 *
 * Potrebné knižnice (Arduino IDE → Správca knižníc):
 *   - WiFiManager  (autor tzapu)
 * Doska: „Generic ESP8266 Module“ (ESP-01), Flash Size 1MB.
 *
 * Vstup: správy tréningovej časomiery cez HC-12 (9600 Bd):
 *   T:<ms>        čas každých 100 ms (bežiaci, po zastavení výsledný; v režime 3 zostávajúci)
 *   STOPL:<ms>    presný čas ľavého terča  ┐ posiela upravená časomiera
 *   STOPP:<ms>    presný čas pravého terča ┘ (casomieraOK_kamera.ino)
 *   M:<režim>     reset / príprava: 1 = LP, 2 = LP+PP, 3 = odpočet 4 min, 0 = chyba senzora
 *   ERROR:SENSOR_STUCK
 * Výstup do aplikácie:
 *   režim 2:  "16.12;16.84"  (ľavý;pravý – každý sa zastaví pri svojom terči)
 *   režim 1:  "16.12"        (iba overlay 1)
 *   režim 3:  "3:59"         (odpočet, iba overlay 1)
 *   pri štarte START, pri resete CLEAR
 *   → v aplikácii: Nastavenia → UDP → Rozdelenie textu = „Rozdeliť oddeľovačom“, oddeľovač ;
 *     popisy „Ľ:“ / „P:“ nastav ako prefix overlayu 1 a 2.
 *
 * Zapojenie: RX ESP-01 na TX výstup Arduina displeja (ten vypisuje prijaté správy)
 * alebo priamo na TX modulu HC-12 displeja. Pri 5 V daj na RX ESP delič napätia
 * (napr. 1k + 2k2) – ESP-01 znesie iba 3,3 V. Spoločná zem (GND).
 *
 * Príkazy aplikácie: START, STOP, MARK, CLEAR, CLEAR:1, TEAM:názov
 */

#include <ESP8266WiFi.h>
#include <WiFiUdp.h>
#include <WiFiManager.h>

// ---- nastavenia (musia sedieť s aplikáciou: Nastavenia → UDP) --------------
const uint16_t PHONE_PORT  = 5000;   // „UDP port“ v aplikácii
const uint16_t LISTEN_PORT = 5001;   // „Port, na ktorý sa ohlasuje“ v aplikácii
const uint8_t  MAX_PHONES  = 6;      // koľko telefónov naraz
const uint32_t PHONE_TIMEOUT_MS  = 10000;  // telefón bez ohlásenia 10 s = zabudnúť
const uint32_t DISCOVER_EVERY_MS = 3000;   // hľadanie, kým žiadny telefón nepozná
const bool     ALWAYS_BROADCAST  = false;  // true = posielať aj broadcastom (poistka)
const long     SERIAL_BAUD = 9600;         // rýchlosť HC-12 / časomiery

// ---- spracovanie správ časomiery -------------------------------------------
const bool     FORWARD_RAW    = false;  // true = posielať riadky bez úprav (napr. „T:16840“)
const bool     AUTO_START     = true;   // poslať START, keď sa čas rozbehne od nuly
const bool     HIDE_WHEN_IDLE = true;   // pred štartom neposielať 0.00 – overlay sa ukáže až pri štarte
const char*    LEFT_LABEL     = "";     // text pred ľavým časom (inak prefix overlayu v aplikácii)
const char*    RIGHT_LABEL    = "";     // text pred pravým časom

// ---------------------------------------------------------------------------
struct Phone {
  IPAddress ip;
  uint16_t port;
  uint32_t lastSeen;
  bool used;
};

Phone phones[MAX_PHONES];
WiFiUDP udp;
uint32_t lastDiscover = 0;

IPAddress broadcastIP() {
  IPAddress ip = WiFi.localIP();
  IPAddress m = WiFi.subnetMask();
  return IPAddress((uint8_t)(ip[0] | (uint8_t)~m[0]), (uint8_t)(ip[1] | (uint8_t)~m[1]),
                   (uint8_t)(ip[2] | (uint8_t)~m[2]), (uint8_t)(ip[3] | (uint8_t)~m[3]));
}

uint8_t activePhones() {
  uint8_t n = 0;
  uint32_t now = millis();
  for (uint8_t i = 0; i < MAX_PHONES; i++) {
    if (phones[i].used && now - phones[i].lastSeen < PHONE_TIMEOUT_MS) n++;
  }
  return n;
}

void registerPhone(IPAddress ip, uint16_t port) {
  uint32_t now = millis();
  int8_t freeSlot = -1, oldest = 0;
  for (uint8_t i = 0; i < MAX_PHONES; i++) {
    if (phones[i].used && phones[i].ip == ip) {  // už známy
      phones[i].port = port;
      phones[i].lastSeen = now;
      return;
    }
    if (!phones[i].used && freeSlot < 0) freeSlot = i;
    if (phones[i].lastSeen < phones[oldest].lastSeen) oldest = i;
  }
  uint8_t slot = freeSlot >= 0 ? freeSlot : oldest;
  phones[slot] = { ip, port, now, true };
}

void sendTo(IPAddress ip, uint16_t port, const char* data, size_t len) {
  udp.beginPacket(ip, port);
  udp.write((const uint8_t*)data, len);
  udp.endPacket();
}

// Pošle text všetkým známym telefónom (a broadcastom, ak žiadny nepozná).
void sendText(const String& text) {
  uint32_t now = millis();
  uint8_t sent = 0;
  for (uint8_t i = 0; i < MAX_PHONES; i++) {
    if (phones[i].used && now - phones[i].lastSeen < PHONE_TIMEOUT_MS) {
      sendTo(phones[i].ip, phones[i].port, text.c_str(), text.length());
      sent++;
    }
  }
  if (sent == 0 || ALWAYS_BROADCAST) {
    sendTo(broadcastIP(), PHONE_PORT, text.c_str(), text.length());
  }
}

// Spracuje ohlásenia telefónov (FSCAM:HELLO / FSCAM:HERE).
void handleIncoming() {
  int size = udp.parsePacket();
  if (size <= 0) return;
  char buf[96];
  int len = udp.read(buf, sizeof(buf) - 1);
  if (len <= 0) return;
  buf[len] = 0;
  const char* p = nullptr;
  if (strncmp(buf, "FSCAM:HELLO:", 12) == 0) p = buf + 12;
  else if (strncmp(buf, "FSCAM:HERE:", 11) == 0) p = buf + 11;
  if (p == nullptr) return;
  long port = atol(p);
  if (port <= 0 || port > 65535) port = PHONE_PORT;
  registerPhone(udp.remoteIP(), (uint16_t)port);
}

void discover() {
  const char* msg = "FSCAM:DISCOVER";
  sendTo(broadcastIP(), PHONE_PORT, msg, strlen(msg));
}

void setup() {
  Serial.begin(SERIAL_BAUD);
  WiFi.mode(WIFI_STA);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);  // nižšie oneskorenie, menej stratených paketov
  WiFi.setAutoReconnect(true);

  WiFiManager wm;
  wm.setDebugOutput(false);            // nerušiť sériovú linku časomiery
  wm.setConfigPortalTimeout(180);      // portál 3 min, potom reštart a nový pokus
  if (!wm.autoConnect("FiresportCam-ESP")) {
    ESP.restart();
  }

  udp.begin(LISTEN_PORT);
  discover();
}

// ---------------------------------------------------------------------------
// Tréningová časomiera: T:, STOPL:, STOPP:, M:, ERROR:

uint8_t  tMode = 2;          // režim časomiery (z M:)
unsigned long lastT = 0;
bool haveT = false;
bool attempt = false;        // pokus beží / práve skončil (časy sa zobrazujú)
bool leftDone = false, rightDone = false;
unsigned long leftMs = 0, rightMs = 0;
bool sensorError = false;
uint32_t lastErrorSent = 0;
String lastLine;
uint32_t lastLineAt = 0;

String formatTime(unsigned long ms) {
  unsigned long cs = ms / 10;          // stotiny
  unsigned long m = cs / 6000;
  unsigned long s = (cs / 100) % 60;
  unsigned long c = cs % 100;
  char b[20];
  if (m > 0) snprintf(b, sizeof(b), "%lu:%02lu.%02lu", m, s, c);
  else snprintf(b, sizeof(b), "%lu.%02lu", s, c);
  return String(b);
}

String formatCountdown(unsigned long ms) {
  unsigned long sec = (ms + 999) / 1000;  // zaokrúhlenie nahor ako na displeji
  char b[12];
  snprintf(b, sizeof(b), "%lu:%02lu", sec / 60, sec % 60);
  return String(b);
}

void sendTimes(unsigned long t) {
  String l = String(LEFT_LABEL) + formatTime(leftDone ? leftMs : t);
  if (tMode == 1) {
    sendText(l);                        // iba jeden terč
    return;
  }
  String r = String(RIGHT_LABEL) + formatTime(rightDone ? rightMs : t);
  sendText(l + ";" + r);
}

void newAttempt() {
  attempt = false;
  leftDone = rightDone = false;
  leftMs = rightMs = 0;
}

void handleLine(String msg) {
  msg.trim();
  if (msg.length() == 0) return;

  // Arduino displeja vypisuje každú správu 2× – druhú preskočíme
  if (msg == lastLine && millis() - lastLineAt < 50) return;
  lastLine = msg;
  lastLineAt = millis();

  if (FORWARD_RAW) {
    sendText(msg);
    return;
  }

  if (msg.startsWith("T:")) {
    unsigned long t = (unsigned long)msg.substring(2).toInt();
    bool first = !haveT;                                // ESP zapnuté počas pokusu – neštartovať
    haveT = true;

    if (tMode == 3) {                                   // odpočet 4 min
      lastT = t;
      sendText(formatCountdown(t));
      return;
    }

    if (t + 500 < lastT) newAttempt();                  // čas sa vynuloval
    if (!attempt && t > 0 && (lastT == 0 || first)) {   // čas sa rozbehol = štart
      attempt = true;
      if (AUTO_START && !first) sendText("START");
    }
    lastT = t;

    if (!attempt && t == 0 && HIDE_WHEN_IDLE) return;   // pred štartom nič
    sendTimes(t);
  } else if (msg.startsWith("STOPL:")) {
    leftDone = true;
    leftMs = (unsigned long)msg.substring(6).toInt();
    sendTimes(lastT);
  } else if (msg.startsWith("STOPP:")) {
    rightDone = true;
    rightMs = (unsigned long)msg.substring(6).toInt();
    sendTimes(lastT);
  } else if (msg.startsWith("M:")) {                    // reset časomiery
    int m = msg.substring(2).toInt();
    sensorError = (m == 0);
    if (m >= 1 && m <= 3) tMode = (uint8_t)m;
    newAttempt();
    lastT = 0;
    sendText("CLEAR");
    if (sensorError) sendText("Chyba senzora");
  } else if (msg.startsWith("ERROR:")) {
    if (millis() - lastErrorSent > 2000) {              // nie častejšie ako raz za 2 s
      lastErrorSent = millis();
      sendText("Chyba senzora");
    }
  }
  // LP; / PP; (prepínanie displeja), cs:, team1:, team2: – pre kameru nepotrebné
}

String line;

void loop() {
  handleIncoming();

  if (activePhones() == 0 && millis() - lastDiscover > DISCOVER_EVERY_MS) {
    lastDiscover = millis();
    discover();
  }

  // riadky od časomiery (HC-12) → spracovanie → aplikácia
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (line.length() > 0) handleLine(line);
      line = "";
    } else if (line.length() < 120) {
      line += c;
    }
  }
}
