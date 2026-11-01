"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/draft_sim.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-draft_sim')}", "--window-size=1400,1000",
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
    def ids():
        return js("(() => (new URLSearchParams(location.hash.slice(1)).get('heroes') || '').split(',').filter(x => x !== '').map(Number))()")

    js("(() => { const m = document.getElementById('mode'); m.value = 'sim'; m.dispatchEvent(new Event('change')); })()")
    wait_idle()
    check("режим симуляции включается", "sim=" in js("location.hash") and not js("document.getElementById('hints-wrap').hidden"), js("location.hash"))
    check("симуляция начинается с пустого драфта", ribbon() == [], ribbon())

    FALLBACK = ["Pudge", "Lion", "Sven", "Tiny", "Lina", "Axe", "Mirana", "Zeus", "Kunkka", "Razor", "Riki", "Slardar"]
    def our_move():
        before = len(ids())
        pick = "ПИК" in js("document.getElementById('turn').textContent")
        time.sleep(0.5)
        js(f"(() => {{ const b = document.querySelector('{'#picks' if pick else '#bans'} .hero-pick'); if (b) b.click(); }})()")
        time.sleep(0.4)
        if len(ids()) == before:
            for h in FALLBACK:
                type_enter(h)
                if state()["warn"]:
                    type_enter(h)
                if len(ids()) > before:
                    break

    disabled_on_bot = None
    deadline = time.time() + 150
    while len(ids()) < 24 and time.time() < deadline:
        if js("document.getElementById('search').disabled"):
            if disabled_on_bot is None:
                disabled_on_bot = "соперник думает" in js("document.getElementById('turn').textContent")
            time.sleep(0.4)
            continue
        our_move()
    h = ids()
    check("на ходу соперника ввод закрыт и видно «соперник думает»", disabled_on_bot is True)
    check("драфт доходит до 24 ходов", len(h) == 24, len(h))
    check("герои не повторяются", len([x for x in h if x > 0]) == len(set(x for x in h if x > 0)), h)
    time.sleep(2.5)
    res = js("document.getElementById('sim-result').innerText")
    check("после драфта видна оценка с итогом", "драфт" in res and "Линии" in res and "Мид-гейм" in res and "Кайт" in res, res[:200])
    check("старая таблица линий скрыта в симуляции", js("document.getElementById('lanes-box').hidden"))
    js("document.getElementById('summary').scrollIntoView()")
    time.sleep(0.5)
    shot = call("Page.captureScreenshot")
    open(os.path.join(SCRATCH, "cdp_sim_summary.png"), "wb").write(base64.b64decode(shot["data"]))
    check("соперник раскрыл расстановку (rev=1)", "rev=1" in js("location.hash"), js("location.hash"))

    # правка позиции соперника после драфта пересчитывает оценку
    before = js("document.querySelector('#sim-result table').innerText")
    js("""(() => { const sel = document.querySelector('#their-slots .slot-assign');
        const cur = sel.value; const other = [...sel.options].map(o => o.value).find(v => v && v !== cur);
        sel.value = other; sel.dispatchEvent(new Event('change', {bubbles: true})); })()""")
    time.sleep(2.5)
    after = js("document.querySelector('#sim-result table').innerText")
    check("правка позиции после драфта пересчитывает оценку", before != after)
    labels = js("(() => [...document.querySelectorAll('#their-slots .slot-hero .small.muted')].map(e => e.textContent.trim()))()")
    check("герои соперника поменялись местами: двое «вручную», остальные «расстановка соперника»",
          labels.count("вручную") == 2 and labels.count("расстановка соперника") == 3, labels)

    # обновление страницы сохраняет симуляцию
    n = len(ids())
    call("Page.reload")
    time.sleep(3.5)
    check("после обновления тот же драфт в режиме симуляции", len(ids()) == n and js("document.getElementById('mode').value") == "sim")

    # отмена откатывает наш последний ход вместе с ответами соперника
    order = js("JSON.parse(document.getElementById('draft').dataset.order)")
    last_ours = max(i for i, s in enumerate(order) if s["firstTeam"])
    js("document.getElementById('undo').click()")
    time.sleep(1.2)
    check("отмена откатывает к нашему последнему ходу", len(ids()) == last_ours, (len(ids()), last_ours))
    check("после отмены снова наш ход и оценка скрыта", not js("document.getElementById('search').disabled")
          and js("document.getElementById('summary').hidden"))

    # без подсказок
    js("(() => { const c = document.getElementById('no-hints'); c.checked = true; c.dispatchEvent(new Event('change')); })()")
    time.sleep(0.5)
    check("«без подсказок» скрывает советы", js("document.getElementById('panel-picks').offsetParent === null")
          and js("document.getElementById('our-slots').offsetParent !== null"))
    js("(() => { const c = document.getElementById('no-hints'); c.checked = false; c.dispatchEvent(new Event('change')); })()")

    # ручной режим не потерян
    js("(() => { const m = document.getElementById('mode'); m.value = 'manual'; m.dispatchEvent(new Event('change')); })()")
    time.sleep(1)
    check("в ручном режиме свой драфт", "sim=" not in js("location.hash") and len(ids()) != last_ours, js("location.hash"))
    shot = call("Page.captureScreenshot")
    open(os.path.join(SCRATCH, "cdp_sim.png"), "wb").write(base64.b64decode(shot["data"]))
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
