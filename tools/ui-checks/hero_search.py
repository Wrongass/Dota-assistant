"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/hero_search.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'cdp-prof5')}", "--window-size=1400,1000",
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

    def sug(q):
        js("(() => { const s=document.getElementById('search'); s.value=%s; s.dispatchEvent(new Event('input')); })()" % json.dumps(q))
        time.sleep(0.2)
        return js("(() => [...document.querySelectorAll('#suggest li')].map(li => li.textContent.trim()))()")
    cases = [("скай", "Skywrath Mage"), ("скайрат", "Skywrath Mage"), ("тб", "Terrorblade"), ("км", "Crystal Maiden"),
             ("бб", "Bristleback"), ("шш", "Shadow Shaman"), ("лс", "Lifestealer"), ("рики", "Riki"), ("нага", "Naga Siren"),
             ("тусик", "Tusk"), ("венге", "Vengeful Spirit"), ("войд спирит", "Void Spirit"), ("wr", "Windranger"),
             ("es", "Earthshaker"), ("кунк", "Kunkka"), ("вк", "Wraith King")]
    for q, want in cases:
        got = sug(q)
        check(f"«{q}» → {want} первым", bool(got) and got[0].startswith(want), got[:3])
    got = sug("войд")
    check("«войд» → оба Void", any(x.startswith("Faceless Void") for x in got) and any(x.startswith("Void Spirit") for x in got), got)
    got = sug("шадоу")
    check("«шадоу» → три Shadow", sum(x.startswith("Shadow") for x in got) == 3, got)
    got = sug("фч")  # «ax» в русской раскладке
    check("раскладка: «фч» → Axe", bool(got) and got[0].startswith("Axe"), got)
    got = sug("qwqwqw")
    check("нет совпадений — «Не нашёл героя»", got == ["Не нашёл героя «qwqwqw»"], got)
    type_enter("Kunkka")
    got = sug("кунк")
    check("взятый герой — серой строкой с номером хода", got and "уже забанен (ход 1)" in got[-1], got)
    # контрпики
    call("Page.navigate", url="http://127.0.0.1:8070/counters")
    time.sleep(2)
    js("(() => { const i=document.querySelector('input[name=h]'); i.value='снайп'; i.dispatchEvent(new Event('input')); })()")
    time.sleep(0.4)
    got = js("(() => [...document.querySelectorAll('.hero-input .suggest li')].map(li => li.textContent.trim()))()")
    check("контрпики: «снайп» подсказывает Sniper", bool(got) and got[0] == "Sniper", got)
    js("(() => { const f=document.querySelector('.counters-form'); f.requestSubmit(); })()")
    time.sleep(2)
    url = js("location.href")
    body = js("document.querySelector('main').innerText")
    check("контрпики: «снайп» отправляется как Sniper", "h=Sniper" in url and "Против" in body, url)
    call("Page.navigate", url="http://127.0.0.1:8070/counters")
    time.sleep(2)
    js("(() => document.querySelector('.counters-form').requestSubmit())()")
    time.sleep(1)
    check("контрпики: пустой запрос не отправляется", js("location.search") == "" and "Укажите героя" in js("document.querySelector('input[name=h]').placeholder"))
    print(f"{sum(1 for _, ok, _ in results if ok)}/{len(results)} проверок прошли")
finally:
    proc.kill()
