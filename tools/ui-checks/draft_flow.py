"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/draft_flow.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'cdp-prof2')}", "--window-size=1400,1000",
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

    # 1. поиск по началу русского слова и список вариантов
    js("(() => { const s=document.getElementById('search'); s.value='кунк'; s.dispatchEvent(new Event('input')); })()")
    wait_idle()
    st = state()
    check("«кунк» находит Kunkka", st["suggest"] and st["suggest"][0].startswith("Kunkka"), st["suggest"][:3])
    js("(() => { const s=document.getElementById('search'); s.value='sp'; s.dispatchEvent(new Event('input')); })()")
    wait_idle()
    st = state()
    check("неоднозначный ввод даёт список из нескольких", len(st["suggest"]) >= 3, st["suggest"])
    # стрелка вниз выбирает второй вариант
    second = st["suggest"][1].split(" наш")[0]
    js("document.getElementById('search').dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown', bubbles:true}))")
    js("document.getElementById('search').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}))")
    wait_idle()
    st = state()
    # первый ход — наш бан; второй вариант может быть нашим героем → предупреждение
    if st["warn"]:
        check("предупреждение при бане своего героя", "НАШ бан" in st["warn"], st["warn"])
        js("document.getElementById('search').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}))")
        wait_idle()
        st = state()
    check("↓ + Enter вносит второй вариант", st["ribbon"][:1] == [second], (second, st["ribbon"]))

    # 2. явное предупреждение: наш бан героя нашего игрока (Viper — у Wrongass)
    top = lambda: js("Math.round(document.getElementById('panel-bans').getBoundingClientRect().top)")
    before = top()
    js("(() => { const s=document.getElementById('search'); s.value='viper'; s.dispatchEvent(new Event('input')); })()")
    wait_idle()
    check("подсказка поиска не сдвигает «Нам банить»", top() == before, (before, top()))
    type_enter("viper")
    st = state()
    check("бан своего героя требует подтверждения", "НАШ бан" in st["warn"] and len(st["ribbon"]) == 1, st["warn"])
    check("предупреждение не сдвигает «Нам банить»", top() == before, (before, top()))
    btn = js("(() => [...document.querySelectorAll('#warn button')].map(b => b.textContent))()")
    check("в предупреждении кнопки «Забанить Viper» и «Отмена»", btn == ["Забанить Viper", "Отмена"], btn)
    js("document.querySelector('#warn button[data-act=confirm]').click()")
    wait_idle()
    st = state()
    check("кнопка в предупреждении банит", len(st["ribbon"]) == 2 and st["ribbon"][1] == "Viper", st["ribbon"])
    toast = js("(() => document.getElementById('toast').hidden ? '' : document.getElementById('toast').innerText)()")
    check("после хода сообщение «Внесено … Отменить»", "Внесено: бан Viper" in toast and "Отменить" in toast, toast)
    # двойной клик: сразу после хода клик по списку советов не принимается
    n = js("""(() => { const s=document.getElementById('search'); s.value='Bounty Hunter'; s.dispatchEvent(new Event('input'));
        s.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true}));
        const stale = document.querySelectorAll('.advice-panel.stale').length;
        const b = document.querySelector('#their-likely-bans .hero-pick, #bans .hero-pick');
        if (b) b.click();
        return stale; })()""")
    wait_idle()
    st = state()
    check("после хода списки погашены и второй клик не вносит героя", n >= 1 and len(st["ribbon"]) == 3, (n, st["ribbon"]))
    js("document.getElementById('undo').click()")
    wait_idle()

    # 3. их баны (ходы 3–4), наш бан 5, их 6–7
    for h in ["Bounty Hunter", "Winter Wyvern", "Techies", "Lina", "Sniper"]:
        type_enter(h)
        if state()["warn"]:
            type_enter(h)
    st = state()
    check("после 7 банов — наш первый пик", "ПИК НАШ" in st["turn"], st["turn"])
    check("на нашем пике в фокусе «Нам брать»", st["focus"] == ["Нам брать"], st["focus"])
    order = js("(() => [...document.querySelectorAll('.center .advice-panel')].map(p => p.id + ':' + (p.style.order || '')))()")
    check("панели не переставляются", all(x.endswith(':') for x in order), order)

    # 4. наш пик не из пулов → предупреждение (Meepo)
    type_enter("meepo")
    st = state()
    check("пик не из наших пулов просит подтверждения", "нет в наших пулах" in st["warn"], st["warn"])
    js("document.getElementById('search').dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', bubbles:true}))")
    wait_idle()
    type_enter("lich")
    st = state()
    check("Lich внесён нашим пиком", "Lich" in st["ourPicks"], st["ourPicks"])
    time.sleep(0.5)
    keys = js("(() => [...document.querySelectorAll('#picks li')].slice(0,10).map(li => [...li.querySelectorAll('.reason')].map(r => r.textContent)))()")
    check("в «Нам брать» причины драфта идут первыми", any(r and r[0].startswith(("контрит", "связка", "линия", "пик-отказ")) for r in keys), keys[:3])
    more = js("(() => { const m = document.querySelector('#picks .reason.more'); if (!m) return null; const li = m.closest('li'); const n = li.querySelectorAll('.reason').length; m.click(); return [n, li.querySelectorAll('.reason').length]; })()")
    check("клик по «+N» показывает остальные причины", more is None or more[1] > more[0], more)
    check("на их пике в фокусе их панель", any("Что они возьмут" in f for f in st["focus"]), st["focus"])

    # 5. исправить ход 3 кликом по ленте
    js("document.querySelectorAll('#ribbon .slot')[2].click()")
    wait_idle()
    st = state()
    check("клик по ходу 3 включает исправление", "Исправляете ход 3" in st["hint"], st["hint"])
    type_enter("dragon knight")
    st = state()
    check("ход 3 заменён, остальные на месте", st["ribbon"][2] == "Dragon Knight" and len(st["ribbon"]) == 8, st["ribbon"])

    # 6. ручное назначение: Lich → Babayka
    js("""(() => { const s = document.querySelector('.slot-assign[data-player="Babayka"]'); s.value = '31';
        s.dispatchEvent(new Event('change', {bubbles: true})); })()""")
    time.sleep(1)
    st = state()
    lich_on_babayka = js("""(() => { const slot = document.querySelector('.slot-assign[data-player="Babayka"]').closest('.pslot');
        return slot.innerText; })()""")
    check("Lich вручную на Babayka", "Lich" in lich_on_babayka and "вручную" in lich_on_babayka, lich_on_babayka)

    # 7. отмена хода и перезагрузка: драфт и назначение сохраняются
    js("document.getElementById('undo').click()")
    wait_idle()
    st = state()
    check("отмена убирает последний ход", len(st["ribbon"]) == 7, st["ribbon"])
    type_enter("lich")
    js("""(() => { const s = document.querySelector('.slot-assign[data-player="Babayka"]'); s.value = '31';
        s.dispatchEvent(new Event('change', {bubbles: true})); })()""")
    time.sleep(1)
    call("Page.reload")
    time.sleep(2.5)
    st = state()
    lich_on_babayka = js("""(() => document.querySelector('.slot-assign[data-player="Babayka"]').closest('.pslot').innerText)()""")
    check("после перезагрузки драфт и назначение на месте", len(st["ribbon"]) == 8 and "вручную" in lich_on_babayka, (st["ribbon"], lich_on_babayka))
    # карточка игрока соперника по клику на ник
    js("document.querySelector('#their-slots .nick').click()")
    time.sleep(1.5)
    drawer = js("(() => document.getElementById('drawer').hidden ? '' : document.getElementById('drawer-body').innerText)()")
    check("клик по нику открывает карточку игрока", "Чем играет сейчас" in drawer and "Всегда может достать" in drawer, drawer[:120])

    shot = call("Page.captureScreenshot")
    open(os.path.join(SCRATCH, "cdp2.png"), "wb").write(base64.b64decode(shot["data"]))
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
