import subprocess, json, os, time, threading

ROOT = "/Users/hedi/Documents/Github/picolo-rpg"
FILE_URI = f"file://{ROOT}/src/conf.tl"
BAD_SRC = """function love.conf(t: love.Configuration)
  local x: string = 5
end
"""

def make_msg(obj):
    body = json.dumps(obj)
    data = body.encode("utf-8")
    return f"Content-Length: {len(data)}\r\n\r\n".encode("utf-8") + data

proc = subprocess.Popen(
    ["teal-language-server", "-L", "none"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    cwd=ROOT, bufsize=0,
)

messages = []

def read_exact(n):
    buf = b""
    while len(buf) < n:
        chunk = proc.stdout.read(n - len(buf))
        if not chunk:
            return buf
        buf += chunk
    return buf

def reader_loop():
    while True:
        header = b""
        while not header.endswith(b"\r\n\r\n"):
            b = proc.stdout.read(1)
            if not b:
                return
            header += b
        length = 0
        for line in header.decode("utf-8", "replace").split("\r\n"):
            if line.lower().startswith("content-length"):
                length = int(line.split(":")[1].strip())
        body = read_exact(length)
        try:
            messages.append(json.loads(body.decode("utf-8")))
        except Exception as e:
            messages.append({"_raw": body.decode("utf-8", "replace"), "_err": str(e)})

t = threading.Thread(target=reader_loop, daemon=True)
t.start()

init_req = {
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {
        "processId": os.getpid(),
        "rootUri": f"file://{ROOT}",
        "capabilities": {},
        "workspaceFolders": [{"uri": f"file://{ROOT}", "name": "picolo-rpg"}],
    },
}
proc.stdin.write(make_msg(init_req))
time.sleep(1.5)

initialized_notif = {"jsonrpc": "2.0", "method": "initialized", "params": {}}
proc.stdin.write(make_msg(initialized_notif))
time.sleep(0.5)

did_open = {
    "jsonrpc": "2.0", "method": "textDocument/didOpen",
    "params": {
        "textDocument": {
            "uri": FILE_URI, "languageId": "teal", "version": 1, "text": BAD_SRC,
        }
    },
}
proc.stdin.write(make_msg(did_open))
time.sleep(2)

did_save = {
    "jsonrpc": "2.0", "method": "textDocument/didSave",
    "params": {
        "textDocument": {"uri": FILE_URI},
        "text": BAD_SRC,
    },
}
proc.stdin.write(make_msg(did_save))

time.sleep(6)

proc.terminate()
try:
    proc.wait(timeout=3)
except Exception:
    proc.kill()

stderr_out = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""

print(f"=== MESSAGES RECEIVED ({len(messages)}) ===")
for m in messages:
    print(json.dumps(m, indent=2)[:3000])
    print("---")

print("=== STDERR (last 3000 chars) ===")
print(stderr_out[-3000:])
