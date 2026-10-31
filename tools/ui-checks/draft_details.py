"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/draft_details.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-draft_details')}", "--window-size=1400,1000",
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
    for h in ["Kunkka", "Zeus"]:
        type_enter(h)
        if state()["warn"]: type_enter(h)
    # BUG-05
    js("(() => { const f=document.getElementById('first'); f.value='them'; f.dispatchEvent(new Event('change')); })()")
    wait_idle()
    w = state()["warn"]
    check("смена первого хода посреди драфта просит подтверждения", "Сменить первый ход" in w and js("document.getElementById('first').value") == "us", w)
    js("document.querySelector('#warn button[data-act=cancel]').click()")
    wait_idle()
    check("отмена оставляет первый ход", js("document.getElementById('first').value") == "us" and ribbon() == ["Kunkka", "Zeus"])
    js("(() => { const f=document.getElementById('first'); f.value='them'; f.dispatchEvent(new Event('change')); })()")
    wait_idle()
    js("document.querySelector('#warn button[data-act=confirm]').click()")
    wait_idle()
    turn = state()["turn"]
    check("подтверждение меняет первый ход", js("document.getElementById('first').value") == "them" and "НАШ" in turn, turn)
    # BUG-08: режим исправления в индикаторе
    js("document.querySelectorAll('#ribbon .slot')[0].click()")
    wait_idle()
    turn = state()["turn"]
    check("исправление хода видно в главном индикаторе", turn.startswith("Исправление хода 1"), turn)
    check("кнопка «Сделать пропуском» в режиме исправления", js("document.getElementById('skip').textContent") == "Сделать пропуском")
    js("document.getElementById('search').dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}))")
    wait_idle()
    # E-22: сброс с отсчётом
    js("document.getElementById('reset').click()")
    time.sleep(1.2)
    label = js("document.getElementById('reset').textContent")
    check("сброс показывает обратный отсчёт", label.startswith("Точно сбросить? (") and "(5)" in label, label)
    time.sleep(2.5)
    js("document.getElementById('reset').click()")
    wait_idle()
    check("второй клик через 3,5 с ещё сбрасывает", ribbon() == [], ribbon())
    # BUG-07: конец драфта
    js("(() => { const f=document.getElementById('first'); f.value='us'; f.dispatchEvent(new Event('change')); })()")
    wait_idle()
    ids = js("(() => [...document.querySelectorAll('#grid .hero-tile')].slice(0,24).map(b => b.dataset.id).join(','))()")
    js(f"location.hash = 'first=us&heroes={ids}'; location.reload();")
    time.sleep(3)
    check("после конца драфта поиск и пропуск отключены", js("document.getElementById('search').disabled") and js("document.getElementById('skip').disabled"), state()["turn"])
    # BUG-10
    lbl = js("(() => (document.querySelector('#our-slots .recent')||{}).textContent || '')()")
    check("подпись чипов — «2 мес»", lbl.startswith("2 мес"), lbl[:20])
    # BUG-06: копирование по HTTP (headless — без прав на буфер, проверяем, что не «не удалось»)
    js("document.getElementById('copy').click()")
    time.sleep(0.8)
    copied = js("(() => document.getElementById('copied').innerText || (document.querySelector('#copied textarea')||{}).value || '')()")
    check("«Скопировать текстом» не пишет «не удалось»", copied and "не удалось" not in copied, copied[:60])
    # E-23: пропущенный наш пик в итоге
    ids2 = ids.split(",")
    ids2[7] = "0"  # ход 8 — наш первый пик (мы ходим первыми)
    js(f"location.hash = 'first=us&heroes={','.join(ids2)}'; location.reload();")
    time.sleep(3)
    w = js("(() => document.getElementById('summary-warn').innerText)()")
    check("итог предупреждает о пропущенном пике", "пик 8 пропущен" in w, w)
    # UX-06: сетку героев можно свернуть, выбор запоминается
    js("document.getElementById('grid-box').open = false")
    time.sleep(0.3)
    call("Page.reload")
    time.sleep(2.5)
    check("свёрнутая сетка героев остаётся свёрнутой", js("document.getElementById('grid-box').open") is False)
    js("document.getElementById('grid-box').open = true")
    # CNT-06: клик по их герою — «Чем отвечать»
    js("location.hash = 'first=us&heroes=23,22,1,2,31,32,33,42,106'; location.reload();")
    time.sleep(3)
    js("(() => { const b = document.querySelector('#their-slots .counter-open'); if (b) b.click(); })()")
    time.sleep(1.5)
    d = js("(() => document.getElementById('drawer').hidden ? '' : document.getElementById('drawer-body').innerText)()")
    check("клик по их герою открывает «Чем отвечать»", "Чем отвечать на" in d and "Из наших героев" in d, d[:120])
    shot = call("Page.captureScreenshot")
    open(os.path.join(SCRATCH, "cdp_small.png"), "wb").write(base64.b64decode(shot["data"]))
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
