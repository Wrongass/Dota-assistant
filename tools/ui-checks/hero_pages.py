"""Проверка сайта в headless Chrome через DevTools Protocol (минимальный websocket-клиент на stdlib).
Нужны запущенный сайт (./gradlew web, http://127.0.0.1:8070) и Chrome. Запуск: python tools/ui-checks/hero_pages.py"""
import base64, json, os, socket, struct, subprocess, sys, tempfile, time, urllib.request

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT = 9333
SCRATCH = os.path.join(tempfile.gettempdir(), "dota-ui-checks")
os.makedirs(SCRATCH, exist_ok=True)
proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={PORT}",
                         f"--user-data-dir={os.path.join(SCRATCH, 'chrome-profile-hero_pages')}", "--window-size=1400,1000",
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
    results = []
    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print(("OK  " if ok else "FAIL") + " " + name + (" | " + str(detail) if detail else ""))

    def go(url, wait=2.0):
        call("Page.navigate", url="http://127.0.0.1:8070" + url)
        time.sleep(wait)

    # список героев: поиск по сленгу, Enter открывает героя
    go("/heroes")
    n = js("document.querySelectorAll('#hero-grid a').length")
    check("в списке все герои", n and n >= 120, n)
    js("(() => { const i=document.getElementById('hero-filter'); i.value='пл'; i.dispatchEvent(new Event('input')); })()")
    time.sleep(0.3)
    shown = js("[...document.querySelectorAll('#hero-grid a')].filter(a => !a.hidden).map(a => a.dataset.name)")
    check("«пл» находит Phantom Lancer", shown and "Phantom Lancer" in shown and len(shown) < 10, shown)
    js("(() => { const i=document.getElementById('hero-filter'); i.value='qwqwqw'; i.dispatchEvent(new Event('input')); })()")
    time.sleep(0.3)
    check("нет совпадений — подпись", js("!document.getElementById('hero-none').hidden"))
    js("(() => { const i=document.getElementById('hero-filter'); i.value='фантом ланс'; i.dispatchEvent(new Event('input'));"
       " i.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true})); })()")
    time.sleep(2)
    check("Enter открывает страницу героя", js("location.pathname") == "/hero/12", js("location.pathname"))
    go("/heroes")
    text = js("document.querySelector('main').innerText")
    check("блок новых предметов есть", "Новые предметы патча" in text and "Skypiercer" in text)
    lifts = js("[...document.querySelectorAll('main .grid3 table')].length")
    warn = js("!!document.querySelector('main p.warn')")
    review = js("[...document.querySelectorAll('main .grid3 a[href*=hero]')].length")
    # пока STRATZ не знает новые предметы, вместо таблиц — предупреждение и герои из ручного разбора патча
    check("новые предметы: таблицы или предупреждение и герои из разбора", (lifts and lifts >= 1) or (warn and review > 5),
          {"tables": lifts, "warn": warn, "review": review})
    check("сильные и слабые предметы патча", "Сильные и слабые предметы патча" in text and "Чаще выигрывают" in text
          and "Реже выигрывают" in text)
    rows = js("[...document.querySelectorAll('main .grid3 table')].map(t => t.querySelectorAll('tbody tr').length)")
    check("у новых предметов цифры по выборке инвентарей", rows and sum(rows) > 20, rows)

    # страница PL: контрпики, предметы против, свои предметы, союзники
    go("/hero/12")
    text = js("document.querySelector('main').innerText")
    for part in ["Кто его контрит", "Кого контрит он", "С кем брать", "Предметы против него", "Crimson Guard",
                 "С какими предметами выигрывает", "Позиция 1"]:
        check(f"PL: «{part}»", part in text)
    links = js("[...document.querySelectorAll('main table a[href^=\"/hero/\"]')].map(a => a.getAttribute('href'))")
    check("PL: герои в таблицах — ссылки, сам PL не встречается", links and "/hero/12" not in links, len(links or []))
    rows = js("[...document.querySelectorAll('main h3')].filter(h => h.textContent.startsWith('Позиция 1'))[0]"
              ".closest('.panel').querySelectorAll('tbody tr').length")
    check("PL: на позиции 1 есть предметы", rows and rows >= 3, rows)
    imgs = js("[...document.querySelectorAll('img.item-ico')].length")
    check("иконки предметов", imgs and imgs > 3, imgs)

    go("/hero/42")
    text = js("document.querySelector('main').innerText")
    check("WK: блок по финальным инвентарям с Blink", "По финальным инвентарям" in text and "Blink Dagger" in text)
    go("/hero/108")
    check("Underlord: Ristul's Devotion из разбора патча", "Ristul's Devotion" in js("document.querySelector('main').innerText"))
    go("/hero/44")
    text = js("document.querySelector('main').innerText")
    check("PA: Bloodthorn в «Плохо»", "Плохо" in text and "Bloodthorn" in text)
    mkb = js("[...document.querySelectorAll('.item-advice li')].find(li => li.textContent.includes('Monkey King Bar'))"
             "?.querySelectorAll('.hero-tag').length")
    check("PA: Monkey King Bar без иконки героя Monkey King", mkb == 0, mkb)
    check("PA: в заголовке нет второй иконки", js("document.querySelectorAll('h1 .hero-tag').length") == 0)
    lanes = js("[...document.querySelectorAll('main .grid3 table')].filter(t => t.querySelector('th:last-child')"
               "?.textContent === 'Линий').flatMap(t => [...t.querySelectorAll('tbody tr')].map(r => +r.cells[r.cells.length - 1].textContent))")
    check("PA: на линии только пары от 100 линий", lanes and min(lanes) >= 100, lanes and min(lanes))
    go("/hero/Phantom_Lancer")
    check("адрес по имени героя", js("document.querySelector('h1').textContent").strip().endswith("Phantom Lancer"))
    go("/hero/nosuchhero")
    check("неизвестный герой — 404-страница", "nosuchhero" in js("document.body.innerText"))

    # контрпики: имена ведут на страницы героев
    go("/counters?h=Sniper&pos=0")
    links = js("[...document.querySelectorAll('main table a[href^=\"/hero/\"]')].length")
    check("контрпики: герои — ссылки на страницы", links and links > 5, links)
    check("контрпики: ссылка на страницу их героя", js("!!document.querySelector('main a[href=\"/hero/35\"]')"))

    # монитор 1920: список героев патча — строки во всю ширину (не сетка «Героев»), таблицы прогноза не шире контента
    call("Emulation.setDeviceMetricsOverride", width=1920, height=1000, deviceScaleFactor=1, mobile=False)
    go("/patch")
    w = js("Math.round(document.querySelector('#all-list .hero-row').getBoundingClientRect().width)")
    check("1920 px: «Патч» — герой на всю строку", w and w > 1200, w)
    go("/forecast")
    over = js("(() => { document.querySelectorAll('main details').forEach(d => d.open = true);"
              " const r = document.querySelector('main').getBoundingClientRect().right;"
              " return [...document.querySelectorAll('main table')].filter(t => t.getBoundingClientRect().right > r + 1).length; })()")
    check("1920 px: таблицы прогноза не шире страницы", over == 0, over)
    go("/heroes")
    cols = js("getComputedStyle(document.getElementById('hero-grid')).gridTemplateColumns.split(' ').length")
    check("1920 px: «Герои» — сетка в много колонок", cols and cols >= 8, cols)

    # узкий экран: без горизонтальной прокрутки
    call("Emulation.setDeviceMetricsOverride", width=390, height=800, deviceScaleFactor=1, mobile=True)
    go("/hero/12")
    w = js("[document.documentElement.scrollWidth, window.innerWidth]")
    check("390 px: страница героя без горизонтальной прокрутки", w and w[0] <= w[1] + 1, w)
    print(f"{sum(1 for _, ok, _ in results if ok)}/{len(results)} проверок прошли")
finally:
    proc.kill()
