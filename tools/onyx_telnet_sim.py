#!/usr/bin/env python3
"""Stand-in for the Onyx telnet remote, for exercising the Onyx Remote Android app.

    python3 tools/onyx_telnet_sim.py                 # listens on 0.0.0.0:2323, cuelists 1-20
    python3 tools/onyx_telnet_sim.py --port 23 --cuelists 1,2,5

Point the app's Onyx PC address at this machine (an Android emulator reaches the
host as 10.0.2.2). Replies follow docs/ONYX_TELNET_REFERENCE.md: "200 Welcome"
banner, "200 Ok / ... COMMAND OK / ." on success, "... Cuelist Not Found" for a
missing cuelist, "400-Unknown Command / 400 X" otherwise. Every command is
printed, so you can watch what a button sends. Type "quiet" + Enter to stop
replying (tests the timeout path), "talk" to resume, "drop" to hang up on every
client.
"""
import argparse, socketserver, sys, threading, time


class State:
    cuelists, quiet, clients = set(), False, set()
    lock = threading.Lock()


def reply(line):
    parts = line.strip().split(" ", 1)
    cmd = parts[0].upper()
    args = [a.strip() for a in parts[1].split(",")] if len(parts) > 1 else []
    cl = int(args[0]) if args and args[0].isdigit() else None
    known = cl in State.cuelists
    if cmd in ("GQL", "GTQ", "SQL", "SETQLLEVEL"):
        return ["200 Ok", "GOTOCUE COMMAND OK" if known else "GOTOCUE Cuelist Not Found", "."]
    if cmd in ("PQL", "RQL"):
        return ["222 Ok", "COMMAND OK" if known else "Cuelist Not Found", "."]
    if cmd in ("RAQL", "RAQLDF", "RAO", "RAQLO", "RAQLODF", "CLRCLR"):
        return ["200 Ok", cmd + " COMMAND OK", "."]
    if cmd == "HELP":
        return ["200 Ok", "GQL #  GTQ #,#  PQL #  RQL #  SQL #,#  RAQL  RAQLDF  RAO  RAQLO  RAQLODF  CLRCLR", "."]
    return ["400-Unknown Command", "400 " + parts[0]]


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        peer = "%s:%d" % self.client_address
        with State.lock:
            State.clients.add(self.request)
        print(time.strftime("%H:%M:%S"), peer, "connected")
        try:
            self.wfile.write(b"200 Welcome\r\n")
            for raw in self.rfile:
                line = raw.decode("latin-1").strip()
                if not line:
                    continue
                lines = [] if State.quiet else reply(line)
                print(time.strftime("%H:%M:%S"), peer, "->", line, "" if lines else "(ignored: quiet)",
                      ("<- " + " | ".join(lines)) if lines else "")
                if lines:
                    self.wfile.write(("\r\n".join(lines) + "\r\n").encode("latin-1"))
        except OSError:
            pass
        finally:
            with State.lock:
                State.clients.discard(self.request)
            print(time.strftime("%H:%M:%S"), peer, "disconnected")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address, daemon_threads = True, True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=2323)
    ap.add_argument("--cuelists", default="1-20", help="e.g. 1-20 or 1,2,5")
    a = ap.parse_args()
    for part in a.cuelists.split(","):
        lo, _, hi = part.partition("-")
        State.cuelists.update(range(int(lo), int(hi or lo) + 1))
    srv = Server(("0.0.0.0", a.port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("Onyx telnet sim on :%d, cuelists %s" % (a.port, a.cuelists))
    for cmd in sys.stdin:
        cmd = cmd.strip().lower()
        if cmd == "quiet":
            State.quiet = True
            print("not replying")
        elif cmd == "talk":
            State.quiet = False
            print("replying")
        elif cmd == "drop":
            with State.lock:
                for s in list(State.clients):
                    s.close()
            print("dropped all clients")


if __name__ == "__main__":
    main()
