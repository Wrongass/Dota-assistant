"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/draft_series.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-draft_url')}", "--window-size=1400,1000",
                         "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(50):
        try:
            targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json"))
            page = next(t for t in targets if t["type"] == "page")
            break
        except Exception:
            time.sleep(0.2)
    ws_url = page["webSocketDebuggerUrl"]
    host, path = ws_url[len("ws://"):].split("/", 1)
    h, p = host.split(":")
    sock = socket.create_connection((h, int(p)))
    key = base64.b64encode(os.urandom(16)).decode()
    sock.send((f"GET /{path} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        resp += sock.recv(1)

    def send(obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        head = bytearray([0x81])
        n = len(data)
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126); head += struct.pack(">H", n)
        else:
            head.append(0x80 | 127); head += struct.pack(">Q", n)
        sock.send(bytes(head) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv_exact(n):
        buf = b""
        while len(buf) < n:
            buf += sock.recv(n - len(buf))
        return buf

    def recv():
        b1, b2 = recv_exact(2)
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", recv_exact(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", recv_exact(8))[0]
        return json.loads(recv_exact(n))

    counter = [0]

    def call(method, **params):
        counter[0] += 1
        send({"id": counter[0], "method": method, "params": params})
        while True:
            msg = recv()
            if msg.get("id") == counter[0]:
                return msg.get("result", {})

    def js(expr):
        r = call("Runtime.evaluate", expression=expr, awaitPromise=True, returnByValue=True)
        return r.get("result", {}).get("value")

    def wait_idle():
        time.sleep(0.6)

    call("Page.enable")
    call("Page.navigate", url="http://127.0.0.1:8070/draft?opponent=SD_Team#first=us&heroes=")
    time.sleep(2.5)
    js("localStorage.clear()")  # чистый старт: драфты прошлых прогонов не мешают
    call("Page.reload")
    time.sleep(2.5)
    js("document.getElementById('reset').click(); document.getElementById('reset').click();")
    wait_idle()

    def type_enter(text, key="Enter"):
        js(f"""(() => {{ const s = document.getElementById('search'); s.value = {json.dumps(text)};
            s.dispatchEvent(new Event('input'));
            s.dispatchEvent(new KeyboardEvent('keydown', {{key: {json.dumps(key)}, bubbles: true}})); }})()""")
        wait_idle()

    def state():
        return js("""(() => ({turn: document.getElementById('turn').textContent,
            warn: document.getElementById('warn').hidden ? '' : document.getElementById('warn').textContent,
            suggest: [...document.querySelectorAll('#suggest li')].map(li => li.textContent.trim()),
            ribbon: [...document.querySelectorAll('#ribbon .slot')].map(x => (x.querySelector('.who')||{}).textContent).filter(Boolean),
            hint: document.getElementById('ribbon-hint').textContent,
            ourPicks: document.getElementById('our-slots').innerText,
            focus: [...document.querySelectorAll('.advice-panel.focus h3')].filter(h => h.offsetParent !== null).map(h => h.textContent.trim()),
            likely: document.getElementById('their-bans-box').hidden }))()""")

    results = []
    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print(("OK  " if ok else "FAIL") + " " + name + (" | " + str(detail) if detail else ""))

    # E-13: смена соперника в адресе, неизвестный соперник с сообщением
    js("(() => { const o=document.getElementById('opponent'); o.value='UPPO_TEAM'; o.dispatchEvent(new Event('change')); })()")
    wait_idle()
    check("смена соперника меняет адрес", "opponent=UPPO_TEAM" in js("location.search"), js("location.href"))
    call("Page.reload")
    time.sleep(2.5)
    check("после F5 открыт тот же соперник", js("document.getElementById('opponent').value") == "UPPO_TEAM")
    call("Page.navigate", url="http://127.0.0.1:8070/draft?opponent=NOPE")
    time.sleep(2.5)
    t = js("(() => document.getElementById('toast').hidden ? '' : document.getElementById('toast').innerText)()")
    check("неизвестный соперник — сообщение", "не найден" in t, t)
    call("Page.navigate", url="http://127.0.0.1:8070/draft?opponent=sd_team")
    time.sleep(2.5)
    check("соперник без учёта регистра", js("document.getElementById('opponent').value") == "SD_Team")
    print(f"{sum(1 for _, ok, _ in results if ok)}/{len(results)} проверок прошли")
finally:
    proc.kill()
