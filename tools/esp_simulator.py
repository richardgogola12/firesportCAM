#!/usr/bin/env python3
"""Simulátor ESP01 na PC – overenie automatického vyhľadania telefónu.

Robí presne to isté ako esp01_firesport.ino:
  - počúva na porte 5001 ohlásenia telefónov (FSCAM:HELLO:<port>:<názov>),
  - kým nepozná žiadny telefón, posiela FSCAM:DISCOVER a čas broadcastom,
  - keď telefón pozná, posiela mu bežiace stopky priamo na jeho IP.

Použitie:  python3 esp_simulator.py        (Ctrl+C = koniec)
PC a telefón musia byť v rovnakej sieti; na PC povoľ UDP 5001 vo firewalle.
"""
import socket
import time

PHONE_PORT = 5000
LISTEN_PORT = 5001
TIMEOUT = 10.0

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
sock.bind(("", LISTEN_PORT))
sock.setblocking(False)

phones = {}  # ip -> (port, name, last_seen)
start = time.time()
last_discover = 0.0
last_send = 0.0
print(f"Čakám na telefóny (port {LISTEN_PORT})…")

try:
    while True:
        now = time.time()
        try:
            data, (ip, _port) = sock.recvfrom(256)
            msg = data.decode("utf-8", "replace")
            for prefix in ("FSCAM:HELLO:", "FSCAM:HERE:"):
                if msg.startswith(prefix):
                    parts = msg[len(prefix):].split(":", 1)
                    port = int(parts[0]) if parts[0].isdigit() else PHONE_PORT
                    name = parts[1] if len(parts) > 1 else ""
                    if ip not in phones:
                        print(f"Nájdený telefón {ip}:{port} ({name})")
                    phones[ip] = (port, name, now)
        except BlockingIOError:
            pass

        for ip in [i for i, v in phones.items() if now - v[2] > TIMEOUT]:
            print(f"Telefón {ip} sa neozýva – zabúdam")
            del phones[ip]

        if not phones and now - last_discover > 3:
            last_discover = now
            sock.sendto(b"FSCAM:DISCOVER", ("255.255.255.255", PHONE_PORT))

        if now - last_send > 0.1:
            last_send = now
            t = now - start
            text = f"L {t:6.2f};P {t * 1.02:6.2f}".encode("utf-8")
            if phones:
                for ip, (port, _n, _s) in phones.items():
                    sock.sendto(text, (ip, port))
            else:
                sock.sendto(text, ("255.255.255.255", PHONE_PORT))
        time.sleep(0.01)
except KeyboardInterrupt:
    pass
