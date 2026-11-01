"""Run peer-cli in the background and feed it commands from a file.

  python -I cli-driver.py <peer-cli.exe> <dir> <cmdfile> <outfile> [run args...]

Every line appended to <cmdfile> after the start is sent to peer-cli's stdin; everything it prints
goes to <outfile>. Stops when a line "quit" is sent or the process ends. Lines already in <cmdfile>
are not sent again, so a restart replays neither the last "quit" nor old sends.
"""
import subprocess
import sys
import threading
import time

exe, d, cmdfile, outfile = sys.argv[1:5]
extra = sys.argv[5:]
open(cmdfile, "a").close()
out = open(outfile, "a", encoding="utf-8", buffering=1)
p = subprocess.Popen([exe, d, "run", *extra], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)


def pump():
    for line in p.stdout:
        out.write(time.strftime("%H:%M:%S ") + line)


threading.Thread(target=pump, daemon=True).start()
with open(cmdfile, encoding="utf-8") as f:
    f.read()
    pos = f.tell()
while p.poll() is None:
    with open(cmdfile, encoding="utf-8") as f:
        f.seek(pos)
        new = f.read()
        pos = f.tell()
    for line in new.splitlines():
        if not line.strip():
            continue
        out.write(time.strftime("%H:%M:%S ") + "> " + line + "\n")
        p.stdin.write(line + "\n")
        p.stdin.flush()
        if line.strip() == "quit":
            break
    time.sleep(0.3)
time.sleep(1)
p.terminate()
