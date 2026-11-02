"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/draft_series.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-shared_draft')}", "--window-size=1400,1000",
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

    def ribbon():
        return js("(() => [...document.querySelectorAll('#ribbon .slot')].map(x => (x.querySelector('.who')||{}).textContent).filter(Boolean))()")
    type_enter("Kunkka")
    if state()["warn"]:
        type_enter("Kunkka")
    js("document.getElementById('share').click()")
    time.sleep(1.5)
    code = js("new URLSearchParams(location.search).get('share')")
    check("«Поделиться» создаёт ссылку", bool(code) and len(code) == 8, js("location.href"))
    remote = js(f"fetch('/api/share/{code}').then(r => r.json())")
    check("драфт сохранён на сервере", remote and remote.get("heroes") == [23], remote)
    type_enter("Zeus")
    if state()["warn"]:
        type_enter("Zeus")  # Zeus в пуле нашего игрока — бан подтверждаем
    time.sleep(1)
    remote = js(f"fetch('/api/share/{code}').then(r => r.json())")
    check("новый ход отправлен на сервер", remote.get("heroes") == [23, 22], remote.get("heroes"))
    # «другая вкладка» вносит ход
    js(f"""fetch('/api/share/{code}', {{method: 'POST', headers: {{'Content-Type': 'application/json'}},
        body: JSON.stringify({{opponent: 'SD_Team', map: 1, weFirst: true, heroes: [23, 22, 1], assign: {{}}, client: 'other'}})}})""")
    time.sleep(3)
    check("чужой ход подтягивается за 2–3 секунды", ribbon() == ["Kunkka", "Zeus", "Anti-Mage"], ribbon())
    call("Page.navigate", url=f"http://127.0.0.1:8070/draft?share={code}")
    time.sleep(3)
    check("ссылка открывает тот же драфт", ribbon() == ["Kunkka", "Zeus", "Anti-Mage"], ribbon())
    # FEAT-02: история — реальная карта 24.10 против SD Team (пики из лиги) находится
    real = "first=us&heroes=0,0,0,0,0,0,0,135,49,0,0,0,21,96,20,120,86,54,0,0,0,0,31,121"
    call("Page.navigate", url="http://127.0.0.1:8070/draft?opponent=SD_Team#" + real)
    time.sleep(3)
    js("document.getElementById('save-history').click()")
    time.sleep(1.5)
    call("Page.navigate", url="http://127.0.0.1:8070/drafts")
    time.sleep(2)
    page = js("document.querySelector('main').innerText")
    check("драфт в «Истории драфтов» и найдена реальная карта (победа 24.10)", "SD Team" in page and "открыть" in page and "победа" in page, page[:200])
    print(f"{sum(1 for _, ok, _ in results if ok)}/{len(results)} проверок прошли")
finally:
    proc.kill()
