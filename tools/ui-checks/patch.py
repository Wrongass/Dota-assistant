"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/patch.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9342
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-patch')}", "--window-size=1400,1000",
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
    call("Runtime.enable")
    call("Page.navigate", url="http://127.0.0.1:8070/patch")
    time.sleep(3)
    st = js("""(() => ({h1: document.querySelector('h1').textContent,
        active: (document.querySelector('nav.menu a.active') || {}).textContent,
        secs: [...document.querySelectorAll('details.patch-sec')].map(d => ({id: d.id, open: d.open})),
        cards: document.querySelectorAll('.sum-card').length,
        ours: document.querySelectorAll('#ours .hero-row').length,
        theirs: document.querySelectorAll('#theirs .hero-row').length,
        question: (document.querySelector('#questions') || {}).innerText || ''}))()""")
    check("страница патча открывается, пункт меню подсвечен", st["h1"].startswith("Патч 7.") and st["active"] == "Патч", (st["h1"], st["active"]))
    check("разделы на месте (≥ 9), главное открыто", len(st["secs"]) >= 9 and st["secs"][0]["open"], [s["id"] for s in st["secs"]])
    check("в «Главном» карточки изменений (≥ 6)", st["cards"] >= 6, st["cards"])
    check("наши герои и герои соперника есть", st["ours"] >= 10 and st["theirs"] >= 10, (st["ours"], st["theirs"]))
    check("вопрос про OD с ответом и статистикой", "OD" in st["question"] and "п. п." in st["question"], st["question"][:80])
    n = js("""(() => { document.querySelector('#ours .chips .chip[data-nick="Babayka"]').click();
        const rows = [...document.querySelectorAll('#ours .hero-row')];
        return {shown: rows.filter(r => !r.hidden).length, all: rows.length,
                ok: rows.filter(r => !r.hidden).every(r => r.dataset.players.split('|').includes('Babayka'))}; })()""")
    check("фильтр по игроку оставляет только его героев", n["ok"] and 0 < n["shown"] < n["all"], n)
    js("document.querySelector('#ours .chips .chip[data-nick=\"\"]').click()")
    card = js("""(() => { const r = document.querySelector('#ours .hero-row'); r.open = true;
        return new Promise(res => setTimeout(() => res({icons: r.querySelectorAll('.ab-ico').length, comment: !!r.querySelector('.pc-comment'),
            notes: r.querySelectorAll('.pn').length, text: r.innerText.slice(0, 120)}), 300)); })()""")
    check("карточка героя раскрывается: способности с иконками, оценка, строки", card["icons"] >= 1 and card["comment"] and card["notes"] >= 1, card)
    mv = js("""(() => { const m = document.getElementById('movers'); m.open = true;
        return [...m.querySelectorAll('table.movers tbody')].map(t => t.rows.length); })()""")
    check("«Сильнее и слабее»: обе таблицы не пустые", len(mv) == 2 and all(x >= 5 for x in mv), mv)
    it = js("""(() => { const s = document.getElementById('items'); s.open = true; s.scrollIntoView();
        return new Promise(res => setTimeout(() => { const imgs = [...s.querySelectorAll('.item-card .it-ico')];
          res({cards: s.querySelectorAll('.item-card').length, newCount: s.querySelectorAll('.badge.new').length,
               loaded: imgs.filter(i => i.complete && i.naturalWidth > 0).length}); }, 2500)); })()""")
    check("предметы: новые с иконками", it["newCount"] >= 6 and it["loaded"] >= 6, it)
    f = js("""(() => { const a = document.getElementById('all'); a.open = true; const i = document.getElementById('hero-filter');
        i.value = 'лина'; i.dispatchEvent(new Event('input'));
        const shown = [...document.querySelectorAll('#all-list .hero-row')].filter(r => !r.hidden).map(r => r.querySelector('.hr-name').textContent);
        i.value = ''; i.dispatchEvent(new Event('input')); return shown; })()""")
    check("поиск героя в «Все герои» по сленгу («лина»)", any("Lina" in x for x in f), f)
    call("Page.navigate", url="http://127.0.0.1:8070/patch#hero-76")
    time.sleep(3)
    od = js("""(() => { const r = document.getElementById('hero-76'); return r ? {open: r.open, text: r.innerText.slice(0, 60)} : null; })()""")
    check("ссылка #hero-76 открывает карточку OD", od and od["open"], od)
    shot = call("Page.captureScreenshot", captureBeyondViewport=False)
    open(os.path.join(SCRATCH, "cdp_patch_od.png"), "wb").write(base64.b64decode(shot["data"]))
    call("Page.navigate", url="http://127.0.0.1:8070/patch")
    time.sleep(3)
    shot = call("Page.captureScreenshot", captureBeyondViewport=False)
    open(os.path.join(SCRATCH, "cdp_patch.png"), "wb").write(base64.b64decode(shot["data"]))
    call("Emulation.setDeviceMetricsOverride", width=390, height=844, deviceScaleFactor=1, mobile=True)
    call("Page.reload")
    time.sleep(3)
    w = js("""(() => { document.querySelectorAll('details').forEach(d => d.open = true);
        return new Promise(res => setTimeout(() => res({page: document.documentElement.scrollWidth, win: window.innerWidth}), 500)); })()""")
    check("на телефоне страница не шире экрана", w["page"] <= w["win"] + 1, w)
    shot = call("Page.captureScreenshot", captureBeyondViewport=False)
    open(os.path.join(SCRATCH, "cdp_patch_mobile.png"), "wb").write(base64.b64decode(shot["data"]))
    call("Emulation.clearDeviceMetricsOverride")

    # драфт: значки патча на плитках, клик по значку открывает карточку и не вносит ход
    call("Page.navigate", url="http://127.0.0.1:8070/draft")
    time.sleep(3)
    js("localStorage.clear()")
    call("Page.reload")
    time.sleep(3)
    marks = js("document.querySelectorAll('#grid .hero-tile .patch-mark').length")
    check("на плитках драфта значки патча", marks >= 100, marks)
    r = js("""(() => { const m = document.querySelector('#grid .hero-tile[data-id="76"] .patch-mark'); m.click();
        return new Promise(res => setTimeout(() => res({pop: !document.getElementById('patch-pop').hidden,
          text: document.getElementById('patch-pop').innerText.slice(0, 80),
          ribbon: [...document.querySelectorAll('#ribbon .slot .who')].length}), 1200)); })()""")
    check("клик по значку открывает изменения героя и не вносит ход", r["pop"] and "Outworld" in r["text"] or "OD" in r["text"] or "Destroyer" in r["text"], r)
    check("ход не внесён", r["ribbon"] == 0, r["ribbon"])
    shot = call("Page.captureScreenshot", captureBeyondViewport=False)
    open(os.path.join(SCRATCH, "cdp_patch_draft.png"), "wb").write(base64.b64decode(shot["data"]))
    js("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))")
    time.sleep(0.3)
    check("Esc закрывает карточку", js("document.getElementById('patch-pop').hidden"))
    js("document.querySelector('#grid .hero-tile[data-id=\"76\"]').click()")
    time.sleep(1)
    check("клик по самой плитке по-прежнему вносит ход", js("[...document.querySelectorAll('#ribbon .slot .who')].length") == 1)
    js("localStorage.clear()")
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
