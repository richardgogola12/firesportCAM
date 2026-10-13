#!/usr/bin/env python3
"""
Simulátor časomiery ESP32 v1.2 (repozitár casomiera) – posiela binárne rámce RW: (40 bajtov)
10× za sekundu cez UDP a udalosti START / STOP, rovnako ako časomiera. Na test Firesport Cam bez hardvéru.

  python3 casomiera_simulator.py                 # broadcast na port 5000, LP 12.41 / PP 12.87
  python3 casomiera_simulator.py --ip 192.168.4.2 --lp 15.20 --pp 16.05
  python3 casomiera_simulator.py --text          # textový variant $FT

V aplikácii zapni Nastavenia → UDP → „Prijímať protokol časomiery“.
"""
import argparse, socket, struct, time

ST_RUN, ST_STOP = 0x81, 0x82

def frame(ready, t1, s1, t2, s2, channels=2):
    f = bytearray(40)
    f[0:3] = b"RW:"
    f[3] = 1                      # stopky
    f[4] = channels
    f[5] = 0 if t1 >= t2 else 1   # zobrazovaný (väčší) čas
    f[6] = 0x01 if ready else 0x00
    for ch, (ms, st) in enumerate(((t1, s1), (t2, s2))):
        b = 7 + ch * 4
        f[b:b + 3] = struct.pack("<I", ms)[:3]
        f[b + 3] = st
    f[39] = 0x0D
    return bytes(f)

def ft(ready, running, t1, t2):
    st = "R" if ready else ("B" if running else "S")
    sec = lambda ms: "%d.%02d" % (ms // 1000, (ms % 1000) // 10)
    body = "FT,1,%s,%s,%s,%s,0" % (st, sec(max(t1, t2)), sec(t1), sec(t2))
    x = 0
    for c in body: x ^= ord(c)
    return ("$%s*%02X\r\n" % (body, x)).encode()

def main():
    a = argparse.ArgumentParser()
    a.add_argument("--ip", default="255.255.255.255")
    a.add_argument("--port", type=int, default=5000)
    a.add_argument("--lp", type=float, default=12.41)
    a.add_argument("--pp", type=float, default=12.87)
    a.add_argument("--text", action="store_true", help="posielať $FT namiesto binárneho rámca")
    a.add_argument("--no-events", action="store_true", help="neposielať udalosti START/STOP (časomiera v1.1)")
    o = a.parse_args()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    lp_ms, pp_ms = int(round(o.lp * 1000)) // 10 * 10, int(round(o.pp * 1000)) // 10 * 10

    def send(ready, running, t1, s1, t2, s2):
        data = ft(ready, running, t1, t2) if o.text else frame(ready, t1, s1, t2, s2)
        s.sendto(data, (o.ip, o.port))

    print("pripravená 3 s…")
    for _ in range(30):
        send(True, False, 0, 0, 0, 0); time.sleep(0.1)
    print("štart")
    t0 = time.time()
    stop_sent = False
    first = True
    while True:
        el = int((time.time() - t0) * 1000) // 10 * 10
        t1, s1 = (lp_ms, ST_STOP) if el >= lp_ms else (el, ST_RUN)
        t2, s2 = (pp_ms, ST_STOP) if el >= pp_ms else (el, ST_RUN)
        send(False, s1 == ST_RUN or s2 == ST_RUN, t1, s1, t2, s2)
        # udalosti v1.2 – raz, hneď za rámcom s novým stavom
        if first and not o.no_events:
            s.sendto(b"START\r", (o.ip, o.port)); first = False
        if s1 == ST_STOP and s2 == ST_STOP and not stop_sent and not o.no_events:
            s.sendto(b"STOP\r", (o.ip, o.port)); stop_sent = True
        if s1 == ST_STOP and s2 == ST_STOP and el > pp_ms + 5000:
            break
        time.sleep(0.1)
    print("koniec – LP %.2f, PP %.2f" % (lp_ms / 1000, pp_ms / 1000))

if __name__ == "__main__":
    main()
