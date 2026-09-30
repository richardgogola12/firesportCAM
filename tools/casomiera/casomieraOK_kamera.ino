#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <SoftwareSerial.h>
#include <EEPROM.h>

#define EEPROM_ADDR_POWER_SENSOR 0
#define EEPROM_ADDR_DISPLAY_COLOR 1

// LCD 20x4, I2C adresa 0x27
LiquidCrystal_I2C lcd(0x27, 20, 4);

// HC-12 komunikácia
#define HC12_TX 12
#define HC12_RX 11
#define HC12_SET 10
SoftwareSerial hc12(HC12_RX, HC12_TX);  // RX, TX

// Ovládacie piny
const byte pinStopLP = 3;   // INT0
const byte pinStopPP = 2;   // INT1
const byte pinStart = 4;
const byte pinReset = 6;
const byte pinChange = 5;
const byte ledLP = 8;
const byte ledPP = 9;

// Premenné na meranie
volatile uint32_t stopTicksLP = 0;
volatile uint32_t stopTicksPP = 0;
volatile bool lpStopped = false;
volatile bool ppStopped = false;
volatile bool timerRunning = false;

// Počet 10ms "pretečení"
volatile uint32_t overflowCount = 0;

uint8_t mode = 1; // 1 = LP, 2 = LP+PP, 3 = odpočet
unsigned long countdownStart = 0;
const unsigned long countdownDuration = 240000; // 4 minúty v ms
unsigned long lastHC12Send = 0;
unsigned long lastSentTime = 0;

bool displayLP = true;

// Korekčný faktor na presnejšie meranie času
// Upravi podľa reálneho merania (tu je príklad korekcie +1%)
const float correctionFactor = 0.99995;
//const float correctionFactor = 1;

// Menu systém
bool menuActive = false;
uint8_t menuLevel = 0; // 0 = root, 1 = submenu
uint8_t menuIndex = 0;
uint8_t submenuIndex = 0;

String rootMenu[] = {"PowerSensor", "DisplayColor"};
const uint8_t rootMenuSize = 2;

String powerSensorOptions[] = {"Ano", "Nie"};
const uint8_t powerSensorOptionsSize = 2;
uint8_t powerSensorSetting = 0;

String displayColorOptions[] = {"Biela", "Cervena", "Zelena", "Modra", "Ruzova", "Zlta"};
const uint8_t displayColorOptionsSize = 6;
uint8_t displayColorSetting = 0;

unsigned long resetPressStart = 0;
bool resetHeldHandled = false;

bool sensorError = false;

void loadSettingsFromEEPROM() {
  powerSensorSetting = EEPROM.read(EEPROM_ADDR_POWER_SENSOR);
  if (powerSensorSetting >= powerSensorOptionsSize) powerSensorSetting = 0;

  displayColorSetting = EEPROM.read(EEPROM_ADDR_DISPLAY_COLOR);
  if (displayColorSetting >= displayColorOptionsSize) displayColorSetting = 0;

  delay(100);

  //Serial.println("on load powerSensorSetting: " + String(powerSensorSetting));
  //Serial.println("on load displayColorSetting: " + String(displayColorSetting));

  sendColorChange(displayColorSetting);
}

void saveSettingsToEEPROM() {
  EEPROM.update(EEPROM_ADDR_POWER_SENSOR, powerSensorSetting);
  EEPROM.update(EEPROM_ADDR_DISPLAY_COLOR, displayColorSetting);

  //Serial.println("powerSensorSetting: " + String(powerSensorSetting));
  //Serial.println("displayColorSetting: " + String(displayColorSetting));

  sendColorChange(displayColorSetting);
}

void showMenu() {
  lcd.clear();
  if (menuLevel == 0) {
    lcd.setCursor(0, 0);
    lcd.print("Menu:");
    lcd.setCursor(0, 1);
    lcd.print("> " + rootMenu[menuIndex]);
  } else if (menuLevel == 1) {
    lcd.setCursor(0, 0);
    lcd.print(rootMenu[menuIndex]);
    lcd.setCursor(0, 1);
    if (menuIndex == 0) {
      lcd.print("> " + powerSensorOptions[submenuIndex]);
    } else if (menuIndex == 1) {
      lcd.print("> " + displayColorOptions[submenuIndex]);
    }
  }
}


//1ms 0.001s
void setupTimer1() {
  TCCR1A = 0;
  TCCR1B = 0;

  TCCR1B |= (1 << WGM12);               // CTC mód
  TCCR1B |= (1 << CS11) | (1 << CS10);  // Prescaler 64
  OCR1A = 249;                          // 250 tickov → 1ms (0–249)

  TCNT1 = 0;
  TIMSK1 |= (1 << OCIE1A);              // Povolenie prerušení na porovnanie
}

ISR(TIMER1_COMPA_vect) {
  overflowCount++;
}

void sendColorChange(uint8_t ColorIndex){
  //Serial.println("sendColorChange: " + String(ColorIndex));
  //String displayColorOptions[] = {"Biela", "Cervena", "Zelena", "Modra", "Ruzova", "Zlta"};
  // Očakáva formát cs:R,G,B napr. cs:255,0,0
  if(ColorIndex == 0){
    // Biela
    hc12.println("cs:255,255,255");
  }else if(ColorIndex == 1){
    // Cervena
    hc12.println("cs:255,0,0");
  }else if(ColorIndex == 2){
    // Zelena
    hc12.println("cs:0,255,0");
  }else if(ColorIndex == 3){
    // Modra
    hc12.println("cs:0,0,255");
  }else if(ColorIndex == 4){
    // Ruzova
    hc12.println("cs:255,0,255");
  }else if(ColorIndex == 5){
    // Zlta
    hc12.println("cs:255,255,0");
  }else{
    // Biela
    hc12.println("cs:255,255,255");
  }
} 

// ISR pre stop LP (pin 2)
void ISR_stopLP() {
  if (timerRunning && !lpStopped && (mode == 1 || mode == 2)) {
    noInterrupts();
    stopTicksLP = overflowCount;
    lpStopped = true;
    digitalWrite(ledLP, HIGH);
    interrupts();
  }
}

// ISR pre stop PP (pin 3)
void ISR_stopPP() {
  if (mode == 2 && timerRunning && !ppStopped) {
    noInterrupts();
    stopTicksPP = overflowCount;
    ppStopped = true;
    digitalWrite(ledPP, HIGH);
    interrupts();
  }
}

// Reset všetkého
void resetAll() {
  noInterrupts();
  TCNT1 = 0;
  overflowCount = 0;
  stopTicksLP = 0;
  stopTicksPP = 0;
  lpStopped = false;
  ppStopped = false;
  timerRunning = false;
  countdownStart = 0;
  interrupts();

  lastSentTime = 0;
  displayLP = true;

  // Kontrola zaseknutých senzorov
  bool lpPinState = digitalRead(pinStopLP);
  bool ppPinState = digitalRead(pinStopPP);

  sensorError = false; // reset chyby

  if (lpPinState == LOW) {
    lpStopped = true;
    stopTicksLP = overflowCount;
    digitalWrite(ledLP, HIGH);
    sensorError = true;
  } else {
    if(powerSensorSetting == 1){
      digitalWrite(ledLP, LOW);
    }
  }

  if (ppPinState == LOW) {
    ppStopped = true;
    stopTicksPP = overflowCount;
    digitalWrite(ledPP, HIGH);
    sensorError = true;
  } else {
    if(powerSensorSetting == 1){
      digitalWrite(ledPP, LOW);
    }
  }

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print("Casomiera pripr.");
  lcd.setCursor(0, 1);
  lcd.print("Rezim: ");
  if (mode == 1) lcd.print("LP");
  else if (mode == 2) lcd.print("LP + PP");
  else lcd.print("ODPOCET 4min");

  if (sensorError) {
    lcd.setCursor(0, 2);
    lcd.print("CHYBA: zaseknuty");
    lcd.setCursor(0, 3);
    lcd.print("senzor LP/PP!");

    hc12.println("M:0");
  }else{
    hc12.println("M:" + String(mode));
  }

  sendColorChange(displayColorSetting);

}


// Získa aktuálny čas v ms s korekciou
unsigned long getCurrentTimeMillis() {
  noInterrupts();
  uint32_t overflows = overflowCount;
  interrupts();
  return (unsigned long)(overflows * correctionFactor);
}

void setup() {
  pinMode(pinStopLP, INPUT_PULLUP);
  pinMode(pinStopPP, INPUT_PULLUP);
  pinMode(pinStart, INPUT_PULLUP);
  pinMode(pinReset, INPUT_PULLUP);
  pinMode(pinChange, INPUT_PULLUP);
  pinMode(HC12_SET, OUTPUT);
  pinMode(ledLP, OUTPUT);
  pinMode(ledPP, OUTPUT);

  digitalWrite(HC12_SET, HIGH); // Normálny režim HC-12

  lcd.init();
  lcd.backlight();

  setupTimer1();

  attachInterrupt(digitalPinToInterrupt(pinStopLP), ISR_stopLP, FALLING);
  attachInterrupt(digitalPinToInterrupt(pinStopPP), ISR_stopPP, FALLING);

  hc12.begin(9600);

  loadSettingsFromEEPROM();

  resetAll();

  Serial.begin(9600);

  digitalWrite(ledLP, !powerSensorSetting);
  digitalWrite(ledPP, !powerSensorSetting);

}

void loop() {
// ... [pôvodný kód až po loop() – nemení sa]

  // ========= NOVÉ: OVLÁDANIE CEZ SERIAL + FORWARD CMD =========
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();

    // Ak začína na cmd:, pošli na HC12
    if (cmd.startsWith("cmd:")) {
      String payload = cmd.substring(4); // všetko po "cmd:"
      payload.trim();
      if (payload.length() > 0) {
        hc12.println(payload);
        Serial.println("HC12 >> " + payload); // voliteľné pre spätnú kontrolu
      }
      return;
    }

    // Interný príkaz: START
    if (cmd.equalsIgnoreCase("start")) {
      if (!sensorError && !timerRunning && stopTicksLP == 0 && stopTicksPP == 0 && countdownStart == 0) {
        noInterrupts();
        TCNT1 = 0;
        overflowCount = 0;
        lpStopped = false;
        ppStopped = false;
        stopTicksLP = 0;
        stopTicksPP = 0;
        timerRunning = true;
        displayLP = false;
        if (mode == 3) countdownStart = millis();
        interrupts();

        lcd.clear();
        lcd.setCursor(0, 0);
        lcd.print("START! (Serial)");
      }
      return;
    }

    // Interný príkaz: RESET
    if (cmd.equalsIgnoreCase("reset")) {
      resetAll();
      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("RESET (Serial)");
      return;
    }

    // Ignoruj neznáme príkazy
  }



  // ŠTART tlačidlo
  if (!sensorError &&  digitalRead(pinStart) == LOW && !timerRunning && stopTicksLP == 0 && stopTicksPP == 0 && countdownStart == 0) {
      noInterrupts();
      TCNT1 = 0;
      overflowCount = 0;
      lpStopped = false;
      ppStopped = false;
      stopTicksLP = 0;
      stopTicksPP = 0;
      timerRunning = true;
      displayLP = false;
      if (mode == 3) countdownStart = millis();
      interrupts();

      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("START!");
  }

  // ======== VSTUP DO MENU PODRŽANÍM TLAČIDLA (CHANGE) ========
  static unsigned long changePressStart = 0;
  static bool changeHeldHandled = false;

  if (!timerRunning && !lpStopped && !ppStopped && !menuActive) {
    if (digitalRead(pinChange) == LOW) {
      if (changePressStart == 0) {
        changePressStart = millis();
      } else if ((millis() - changePressStart >= 1000) && !changeHeldHandled) {
        menuActive = true;
        menuLevel = 0;
        menuIndex = 0;
        showMenu();
        changeHeldHandled = true;
      }
    } else {
      changePressStart = 0;
      changeHeldHandled = false;
    }
  }

  if (menuActive) {
    // Navigácia – Change = NEXT
    static bool lastChangeStateMenu = HIGH;
    bool currentChangeStateMenu = digitalRead(pinChange);
    if (lastChangeStateMenu == HIGH && currentChangeStateMenu == LOW) {
      if (menuLevel == 0) {
        menuIndex = (menuIndex + 1) % rootMenuSize;
      } else if (menuLevel == 1) {
        if (menuIndex == 0) {
          submenuIndex = (submenuIndex + 1) % powerSensorOptionsSize;
        } else if (menuIndex == 1) {
          submenuIndex = (submenuIndex + 1) % displayColorOptionsSize;
        }
      }
      showMenu();
      delay(200);
    }
    lastChangeStateMenu = currentChangeStateMenu;

    // UPRAVENÁ ČASŤ: RESET správanie podľa trvania stisku
    if (digitalRead(pinReset) == LOW) {
      if (resetPressStart == 0) {
        resetPressStart = millis();
      } else if ((millis() - resetPressStart >= 2000) && !resetHeldHandled) {
        if (menuLevel == 1) {
          if (menuIndex == 0) {
            powerSensorSetting = submenuIndex;
          } else if (menuIndex == 1) {
            displayColorSetting = submenuIndex;
          }

          saveSettingsToEEPROM();

          lcd.clear();
          lcd.setCursor(0, 0);
          lcd.print("Ulozene (dlhy stisk)");
          delay(1000);

          menuLevel = 0;
          submenuIndex = 0;
          showMenu();
        } else if (menuLevel == 0) {
          menuActive = false;
          lcd.clear();
          lcd.setCursor(0, 0);
          lcd.print("Opustenie menu");
          delay(1000);
          resetAll();
        }

        resetHeldHandled = true;
      }
    } else {
      if (resetPressStart > 0 && (millis() - resetPressStart < 500) && !resetHeldHandled) {
        if (menuLevel == 0) {
          menuLevel = 1;
          submenuIndex = 0;
        }

        // V submenu sa pri krátkom stisku už nič neukladá
        showMenu();
      }

      resetPressStart = 0;
      resetHeldHandled = false;
    }

    return;
  }

  // ... [zvyšok pôvodného loopu ostáva nezmenený]
  // Zmena režimu / toggle
  // Meranie LP/PP
  // Odpočet
  // RESET mimo menu
  // Odosielanie HC-12


  // Zmena režimu / toggle v režime 2
  static bool lastChangeState = HIGH;
  bool currentChangeState = digitalRead(pinChange);
  if (lastChangeState == HIGH && currentChangeState == LOW) {
    if (mode == 2 && !timerRunning && lpStopped && ppStopped) {
      displayLP = !displayLP;
      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("STOP (toggle)");

      lcd.setCursor(0, 1);
      if (displayLP) {
        lcd.print("LP: ");
        lcd.print((unsigned long)(stopTicksLP * correctionFactor));
        lcd.print(" ms");
        hc12.println("LP;");
        delay(3000);
      } else {
        lcd.print("PP: ");
        lcd.print((unsigned long)(stopTicksPP * correctionFactor));
        lcd.print(" ms");
        hc12.println("PP;");
        delay(3000);
      }
    } else if (!timerRunning && !lpStopped && !ppStopped) {
      mode++;
      if (mode > 3) mode = 1;
      resetAll();
    }
    delay(300); // debounce
  }
  lastChangeState = currentChangeState;

  // MERANIE pre režimy 1 a 2
  if (timerRunning && (mode == 1 || mode == 2)) {
    if ((mode == 1 && lpStopped) || (mode == 2 && lpStopped && ppStopped)) {
      timerRunning = false;

      lcd.clear();
      lcd.setCursor(0, 0);
      lcd.print("STOP");

      lcd.setCursor(0, 1);
      lcd.print("LP: ");
      lcd.print((unsigned long)(stopTicksLP * correctionFactor));
      lcd.print(" ms");

      if (mode == 2) {
        lcd.setCursor(0, 2);
        lcd.print("PP: ");
        lcd.print((unsigned long)(stopTicksPP * correctionFactor));
        lcd.print(" ms");

        displayLP = (stopTicksLP > stopTicksPP);
      }
    }
  }

  // ========= NOVÉ (Firesport Cam): presné časy terčov =========
  // Pošle raz za pokus STOPL:<ms> / STOPP:<ms> hneď po zásahu terča.
  // Displej tieto správy ignoruje, ESP01 z nich zobrazí ľavý a pravý čas v kamere.
  static bool sentStopLP = false;
  static bool sentStopPP = false;
  if (!lpStopped) sentStopLP = false;
  if (!ppStopped) sentStopPP = false;
  if (!sensorError && (mode == 1 || mode == 2)) {
    if (lpStopped && !sentStopLP && stopTicksLP > 0) {
      hc12.print("STOPL:");
      hc12.println((unsigned long)(stopTicksLP * correctionFactor));
      sentStopLP = true;
    }
    if (mode == 2 && ppStopped && !sentStopPP && stopTicksPP > 0) {
      hc12.print("STOPP:");
      hc12.println((unsigned long)(stopTicksPP * correctionFactor));
      sentStopPP = true;
    }
  }
  // ============================================================

  // ODPOČET režim 3
  if (mode == 3 && timerRunning) {
    unsigned long elapsed = millis() - countdownStart;
    unsigned long remaining = (elapsed >= countdownDuration) ? 0 : countdownDuration - elapsed;

    uint8_t min = remaining / 60000;
    uint8_t sec = (remaining % 60000) / 1000;

    lcd.setCursor(0, 1);
    lcd.print("Cas: ");
    lcd.print(min);
    lcd.print("m ");
    lcd.print(sec);
    lcd.print("s   ");

    if (remaining == 0) {
      timerRunning = false;
      lcd.setCursor(0, 2);
      lcd.print("KONIEC CASU");
    }
  }

  // RESET tlačidlo
  if (digitalRead(pinReset) == LOW) {
    delay(50);
    if (digitalRead(pinReset) == LOW) {
      resetAll();
      delay(300);
    }
  }

  // Odosielanie cez HC-12 každých 100 ms
  if (millis() - lastHC12Send >= 100) {
    lastHC12Send = millis();

    unsigned long timeToSend = 0;

    if (mode == 1 || mode == 2) {
      if (timerRunning) {
        timeToSend = getCurrentTimeMillis();
        lastSentTime = timeToSend;
      } else if (mode == 2 && lpStopped && ppStopped) {
        timeToSend = (displayLP ? (unsigned long)(stopTicksLP * correctionFactor) : (unsigned long)(stopTicksPP * correctionFactor));
        lastSentTime = timeToSend;
      } else {
        unsigned long lpMs = (unsigned long)(stopTicksLP * correctionFactor);
        unsigned long ppMs = (mode == 2) ? (unsigned long)(stopTicksPP * correctionFactor) : 0;
        timeToSend = (lpMs > ppMs) ? lpMs : ppMs;
        lastSentTime = timeToSend;
      }

    } else if (mode == 3) {
      if (timerRunning) {
        unsigned long elapsed = millis() - countdownStart;
        unsigned long remaining = (elapsed >= countdownDuration) ? 0 : countdownDuration - elapsed;
        timeToSend = remaining;
        lastSentTime = timeToSend;
      } else {
        timeToSend = countdownDuration;
      }
    }

    hc12.print("T:");
    hc12.println(timeToSend);
  }

  if (sensorError) {
    // UPRAVENÉ: posielať najviac raz za sekundu (predtým v každom cykle loop – zahltilo HC-12)
    static unsigned long lastErrorSend = 0;
    if (millis() - lastErrorSend >= 1000) {
      lastErrorSend = millis();
      hc12.println("ERROR:SENSOR_STUCK");
    }
    // Nemôžeme spustiť meranie, kým chyba pretrváva
    // Skontrolujeme či už chyba zmizla:
    if (digitalRead(pinStopLP) == HIGH && digitalRead(pinStopPP) == HIGH) {
      sensorError = false;
      resetAll(); // reštart nastavenia, keď chyba zmizla
    }
    return; // zabráni ďalšiemu spracovaniu v loop počas chyby
  }

}
