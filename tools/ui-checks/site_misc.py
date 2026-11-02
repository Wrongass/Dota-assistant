"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/draft_series.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-site_misc')}", "--window-size=1400,1000",
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

    # E-17: сортировка по рангу
    call("Page.navigate", url="http://127.0.0.1:8070/team")
    time.sleep(2)
    ranks = js("""(() => { const th=[...document.querySelectorAll('table.sortable th')].find(t => t.textContent.trim()==='Ранг'); th.click();
        const i=[...th.parentNode.children].indexOf(th); return [...th.closest('table').tBodies[0].rows].map(r => +r.children[i].dataset.sortValue); })()""")
    check("сортировка по рангу — по тиру", ranks == sorted(ranks), ranks)
    check("колонка MMR подписана «Ср. MMR матчей»", "Ср. MMR матчей" in js("document.querySelector('main').innerText"))
    # E-20: время по часовому поясу браузера
    call("Page.navigate", url="http://127.0.0.1:8070/tournament")
    time.sleep(2)
    txt = js("document.querySelector('main').innerText")
    check("время матчей без «UTC» (переведено)", " UTC" not in txt, txt[:80])
    # E-16: меню «Соперники»
    js("document.querySelector('nav.menu details').open = true")
    js("document.querySelector('main').click()")
    time.sleep(0.2)
    check("меню закрывается по клику снаружи", js("document.querySelector('nav.menu details').open") is False)
    # UX-09: названия
    nav = js("document.querySelector('nav.menu').innerText")
    check("вкладки «План драфта» и «Живой драфт»", "План драфта" in nav and "Живой драфт" in nav, nav)
    # E-24, UX-02 на драфте
    call("Page.navigate", url="http://127.0.0.1:8070/draft?opponent=SD_Team#first=us&heroes=23,22")
    time.sleep(2.5)
    bans = js("(() => [...document.querySelectorAll('#our-bans .ban-name, #their-bans .ban-name')].map(x => x.textContent))()")
    check("у иконок банов есть подписи", "Kunkka" in bans and "Zeus" in bans, bans)
    check("легенда метрик есть, «пересчёт» не на экране", js("!!document.querySelector('.legend')") and "пересчёт" not in js("document.getElementById('timing').textContent"))
    # E-19: перечитать данные
    call("Page.navigate", url="http://127.0.0.1:8070/team")
    time.sleep(2)
    js("document.querySelector('#reload-form button').click()")
    time.sleep(4)
    note = js("(() => (document.querySelector('.reload-note')||{}).textContent || '')()")
    check("после «Перечитать данные» сообщение", note.startswith("Данные перечитаны"), note)
    print(f"{sum(1 for _, ok, _ in results if ok)}/{len(results)} проверок прошли")
finally:
    proc.kill()
