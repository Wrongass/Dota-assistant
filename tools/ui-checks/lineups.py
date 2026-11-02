"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/lineups.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9334
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-lineups')}", "--window-size=1400,1000",
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
    call("Page.navigate", url="http://127.0.0.1:8070/draft?opponent=UPPO_TEAM#first=us&heroes=")
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

    def rows():
        return js("(() => [...document.querySelectorAll('#pos-bans li')].map(li => li.innerText.replace(/\\s+/g, ' ').trim()))()")
    def lineups():
        return js("""(() => [...document.querySelectorAll('#their-lineups li .lineup-pick')].map(b => ({
            text: b.innerText.replace(/\\s+/g, ' ').trim(), active: b.classList.contains('active'),
            likely: b.classList.contains('likely'), key: b.dataset.key })))()""")

    def pos_rows():
        return js("""(() => [...document.querySelectorAll('#pos-bans li')].map(li => ({pos: Number(li.dataset.pos),
            text: li.querySelector('.pos-label').innerText.replace(/\\s+/g, ' ').trim()})))()""")

    def their_slots():
        return js("(() => document.getElementById('their-slots').innerText)()")

    # у UPPO TEAM один состав в турнире — блока нет
    check("у соперника с одним составом блока «Состав» нет", js("document.getElementById('their-lineups').hidden"))

    js("location.href = '/draft?opponent=SyberStar#first=us&heroes=23,22,1,2,31,32,33'")
    time.sleep(4)
    ls = lineups()
    check("у SyberStar три состава из турнира", len(ls) == 3, ls)
    auto = js("document.querySelector('#their-lineups .lineups-head .lineup-pick').classList.contains('active')")
    check("по умолчанию «все составы», у каждого вес", auto and all("%" in l["text"] for l in ls), [l["text"][:60] for l in ls])
    check("до их пиков веса равные (33%)", all(l["text"].startswith("33%") for l in ls), [l["text"][:4] for l in ls])
    rows = pos_rows()
    mids = [r["text"] for r in rows if r["pos"] == 2]
    check("баны по позициям: на мид и SuperPoni, и Леонид Ильич, с вероятностью",
          len(mids) == 2 and any("Леонид Ильич" in m for m in mids) and any("SuperPoni" in m for m in mids) and all("%" in m for m in mids), mids)

    # их первый пик Lina: Леонид Ильич играл её в турнире — его состав вероятнее
    js("location.hash = 'first=us&heroes=23,22,1,2,31,32,33,42,25'; location.reload();")
    time.sleep(4)
    ls = lineups()
    top = max(ls, key=lambda l: float(l["text"].split("%")[0]))
    check("после их Lina вероятнее состав с Леонидом Ильичом", "Леонид Ильич" in top["text"] and top["likely"], [l["text"][:70] for l in ls])
    check("слоты соперника — игроки вероятного состава", "Леонид Ильич" in their_slots(), their_slots()[:120])

    # увидели лобби: выбираем состав 25.10 (Elsa на керри)
    js("""[...document.querySelectorAll('#their-lineups li .lineup-pick')].find(b => b.innerText.includes('25.10')).click()""")
    time.sleep(2)
    ls = lineups()
    chosen = [l for l in ls if l["active"]]
    check("выбранный состав подсвечен, веса не показаны", len(chosen) == 1 and "25.10" in chosen[0]["text"] and "%" not in chosen[0]["text"].split("1 ")[0], [l["text"][:40] for l in ls])
    rows = pos_rows()
    check("баны по позициям — по одному игроку на позицию", [r["pos"] for r in rows] == [1, 2, 3, 4, 5] and "Elsa" in rows[0]["text"], [r["text"][:30] for r in rows])
    check("в слотах соперника нет Леонида Ильича", "Леонид Ильич" not in their_slots())
    call("Page.reload")
    time.sleep(4)
    check("выбор состава сохраняется после перезагрузки", any(l["active"] and "25.10" in l["text"] for l in lineups()))
    js("document.querySelector('#their-lineups .lineups-head .lineup-pick').click()")
    time.sleep(2)
    check("«все составы» возвращает веса", all("%" in l["text"] for l in lineups()) and not any(l["active"] for l in lineups()))

    # скрыть мид — скрываются оба игрока мида, в списке скрытых одна позиция
    js("document.querySelector('#pos-bans li[data-pos=\"2\"] .pos-hide').click()")
    time.sleep(0.4)
    rows = pos_rows()
    hid = js("document.getElementById('pos-bans-hidden').innerText")
    check("✕ у мида скрывает обоих кандидатов, «2 мид» один раз", all(r["pos"] != 2 for r in rows) and hid.count("2 мид") == 1, hid)
    js("document.querySelector('#pos-bans-hidden .pos-show-all').click()")
    time.sleep(0.4)
    # «ещё N» раскрывает только свою строку, а не обе строки позиции
    js("document.querySelector('#pos-bans .pos-more').click()")
    time.sleep(0.3)
    opened = js("(() => [...document.querySelectorAll('#pos-bans .pos-more')].filter(b => b.textContent.startsWith('скрыть')).length)()")
    check("«ещё» раскрывает одну строку", opened == 1, opened)

    js("document.getElementById('their-lineups').scrollIntoView()")
    time.sleep(0.4)
    shot = call("Page.captureScreenshot")
    open(os.path.join(SCRATCH, "cdp_lineups.png"), "wb").write(base64.b64decode(shot["data"]))
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
