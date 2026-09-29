#!/usr/bin/env python3
"""Testovací odosielateľ UDP pre Firesport Cam.

Použitie:
  python3 udp_test_sender.py 192.168.1.50            # posiela stopky na port 5000
  python3 udp_test_sender.py 192.168.1.50 5000 "L 12.345;P 13.001"   # jedna správa
  python3 udp_test_sender.py 255.255.255.255         # broadcast do celej siete
"""
import socket
import sys
import time

host = sys.argv[1] if len(sys.argv) > 1 else "255.255.255.255"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5000

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)

if len(sys.argv) > 3:
    sock.sendto(sys.argv[3].encode("utf-8"), (host, port))
    print("odoslané")
    sys.exit(0)

start = time.time()
print(f"Posielam čas na {host}:{port} (Ctrl+C = koniec)")
try:
    while True:
        t = time.time() - start
        left = f"{t:6.2f}"
        right = f"{t * 1.02:6.2f}"
        sock.sendto(f"{left};{right}".encode("utf-8"), (host, port))
        time.sleep(0.05)
except KeyboardInterrupt:
    pass
