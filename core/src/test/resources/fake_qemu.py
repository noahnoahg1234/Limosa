#!/usr/bin/env python3
"""Stand-in for qemu-system-*: serves just enough QMP for VmManager tests."""
import json, socket, sys

args = sys.argv[1:]
qmp = args[args.index("-qmp") + 1]            # tcp:127.0.0.1:PORT,server=on,wait=off
port = int(qmp.split(",")[0].rsplit(":", 1)[1])
print("fake qemu args:", " ".join(args), flush=True)
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port)); srv.listen(4)
status = "running"
while True:
    conn, _ = srv.accept()
    f = conn.makefile("rw")
    f.write(json.dumps({"QMP": {"version": {}, "capabilities": []}}) + "\n"); f.flush()
    for line in f:
        cmd = json.loads(line)["execute"]
        if cmd == "stop": status = "paused"
        elif cmd == "cont": status = "running"
        if cmd == "query-status":
            f.write(json.dumps({"return": {"status": status, "running": status == "running"}}) + "\n")
        else:
            f.write(json.dumps({"return": {}}) + "\n")
        f.flush()
        print("qmp:", cmd, flush=True)
        if cmd in ("quit", "system_powerdown"):
            sys.exit(0)
