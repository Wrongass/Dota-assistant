"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/pos_bans.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-pos_bans')}", "--window-size=1400,1000",
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
    # наш бан на ходу 10: 7 банов, наш пик Wraith King, их пик Lina
    js("location.hash = 'first=us&heroes=23,22,1,2,31,32,33,42,25'; location.reload();")
    time.sleep(3.5)
    r = rows()
    check("в «Нам банить» есть блок «По позициям» на 5 позиций", len(r) == 5 and js("!document.getElementById('pos-bans-box').hidden"), r)
    check("подписи позиций: керри, мид, оффлейн, четвёрка, пятёрка", all(w in " ".join(r) for w in ["керри", "мид", "оффлейн", "четвёрка", "пятёрка"]))
    picked = [x for x in r if "взял" in x]
    check("у игрока, взявшего Lina, строка осталась с пометкой о свапе", len(picked) == 1 and "Lina" in picked[0] and "свапнуть" in picked[0], picked)
    counts = js("(() => [...document.querySelectorAll('#pos-bans li')].map(li => li.querySelectorAll('.hero-pick').length))()")
    check("в строке до 5 героев сразу", all(c <= 5 for c in counts) and max(counts) == 5, counts)
    # «ещё N» раскрывает строку до 10 и сворачивает обратно
    js("document.querySelector('#pos-bans .pos-more').click()")
    time.sleep(0.3)
    n_open = js("(() => document.querySelector('#pos-bans .pos-more').closest('li').querySelectorAll('.hero-pick').length)()")
    lab = js("document.querySelector('#pos-bans .pos-more').textContent")
    check("«ещё N ▾» показывает больше героев (до 10)", 5 < n_open <= 10 and lab.startswith("скрыть"), (n_open, lab))
    js("document.querySelector('#pos-bans .pos-more').click()")
    time.sleep(0.3)
    check("и сворачивает обратно до 5", js("(() => document.querySelector('#pos-bans .pos-more').closest('li').querySelectorAll('.hero-pick').length)()") == 5)
    # скрыть одну позицию (мид) и вернуть
    js("document.querySelector('#pos-bans li[data-pos=\"2\"] .pos-hide').click()")
    time.sleep(0.3)
    left = js("(() => [...document.querySelectorAll('#pos-bans li')].map(li => Number(li.dataset.pos)))()")
    hid = js("document.getElementById('pos-bans-hidden').innerText")
    check("✕ скрывает только мид", left == [1, 3, 4, 5] and "2 мид" in hid, (left, hid))
    call("Page.reload")
    time.sleep(3.5)
    left = js("(() => [...document.querySelectorAll('#pos-bans li')].map(li => Number(li.dataset.pos)))()")
    check("скрытая позиция остаётся скрытой после перезагрузки", left == [1, 3, 4, 5], left)
    js("document.querySelector('#pos-bans-hidden .pos-show').click()")
    time.sleep(0.3)
    left = js("(() => [...document.querySelectorAll('#pos-bans li')].map(li => Number(li.dataset.pos)))()")
    check("↺ возвращает позицию", left == [1, 2, 3, 4, 5] and js("document.getElementById('pos-bans-hidden').innerText") == "", left)
    # клик по герою в блоке — наш бан
    first = js("(() => { const b = document.querySelector('#pos-bans .hero-pick'); return [Number(b.dataset.id), b.textContent.trim()]; })()")
    time.sleep(0.6)
    js("document.querySelector('#pos-bans .hero-pick').click()")
    time.sleep(0.5)
    if not js("document.getElementById('warn').hidden"):
        js("document.querySelector('#warn button[data-act=confirm]').click()")
    time.sleep(1.5)
    ids = js("(() => new URLSearchParams(location.hash.slice(1)).get('heroes').split(',').map(Number))()")
    check("клик по герою в «По позициям» вносит бан", ids[-1] == first[0], (first, ids))
    # скрыть и запомнить
    js("document.querySelector('#pos-bans-box > summary').click()")
    time.sleep(0.3)
    lab = js("getComputedStyle(document.querySelector('#pos-bans-box > summary'), '::after').content")
    check("блок скрывается, подпись «показать ▾»", not js("document.getElementById('pos-bans-box').open") and "показать" in lab, lab)
    call("Page.reload")
    time.sleep(3.5)
    check("после перезагрузки блок остаётся скрытым", not js("document.getElementById('pos-bans-box').open"))
    js("document.querySelector('#pos-bans-box > summary').click()")
    time.sleep(0.3)
    check("и раскрывается обратно", js("document.getElementById('pos-bans-box').open"))
    js("document.getElementById('panel-bans').scrollIntoView()")
    time.sleep(0.4)
    shot = call("Page.captureScreenshot")
    open(os.path.join(SCRATCH, "cdp_pos_bans.png"), "wb").write(base64.b64decode(shot["data"]))
    print(f"{sum(ok for _, ok, _ in results)}/{len(results)} проверок прошли")
finally:
    proc.kill()
