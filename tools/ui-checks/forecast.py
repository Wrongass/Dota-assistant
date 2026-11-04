"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/forecast.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9341
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-forecast')}", "--window-size=1400,1000",
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

    results = []
    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print(("OK  " if ok else "FAIL") + " " + name + (" | " + str(detail) if detail else ""))

    call("Page.enable")
    call("Page.navigate", url="http://127.0.0.1:8070/forecast")
    time.sleep(2.5)
    st = js("""(() => ({h1: document.querySelector('h1').textContent,
        active: (document.querySelector('nav.menu a.active') || {}).textContent,
        intro: document.querySelector('.panel.forecast') ? document.querySelector('.panel.forecast').innerText : '',
        details: [...document.querySelectorAll('details.panel.forecast')].map(d => ({t: d.querySelector('summary').innerText, open: d.open})),
        tables: document.querySelectorAll('.panel.forecast table').length }))()""")
    check("страница прогноза открывается, пункт меню подсвечен", st["h1"] == "Прогноз плей-офф" and st["active"] == "Прогноз", st["active"])
    check("во вступлении следующий матч и таблица шансов", "Следующий матч" in st["intro"] and "Победа в турнире" in st["intro"], st["intro"][:80])
    check("разделы под спойлерами и закрыты", len(st["details"]) >= 8 and not any(d["open"] for d in st["details"]), [d["t"][:20] for d in st["details"]])
    js("document.querySelectorAll('details.panel.forecast')[0].querySelector('summary').click()")
    time.sleep(0.3)
    check("спойлер раскрывается по клику", js("document.querySelectorAll('details.panel.forecast')[0].open"))
    wide = js("""(() => { const p = [...document.querySelectorAll('details.panel.forecast')].find(d => d.innerText.includes('Все пары'));
        p.open = true; return {scroll: p.scrollWidth, client: p.clientWidth, page: document.documentElement.scrollWidth, win: window.innerWidth}; })()""")
    check("широкая таблица прокручивается внутри панели, страница не шире окна", wide["page"] <= wide["win"] + 1, wide)
    js("document.querySelectorAll('details.panel.forecast').forEach(d => d.open = true)")
    time.sleep(0.3)
    check("все таблицы на месте (≥ 10)", js("document.querySelectorAll('.panel.forecast table').length") >= 10)
    shot = call("Page.captureScreenshot", captureBeyondViewport=False)
    open(os.path.join(SCRATCH, "cdp_forecast.png"), "wb").write(base64.b64decode(shot["data"]))
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
