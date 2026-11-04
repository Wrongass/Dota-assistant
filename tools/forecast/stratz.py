"""Минимальный клиент STRATZ GraphQL (stdlib): токен из STRATZ_TOKEN или config/tokens.env, пауза 1.1 с между
запросами, в лог — остаток лимитов, повтор 429/5xx с нарастающей паузой."""
import json, os, re, sys, time, urllib.error, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))


def token():
    if os.environ.get('STRATZ_TOKEN'):
        return os.environ['STRATZ_TOKEN']
    for f in ('.env', os.path.join('config', 'tokens.env')):
        p = os.path.join(ROOT, f)
        if os.path.exists(p):
            m = re.search(r'^STRATZ_TOKEN=(.+)$', open(p, encoding='utf-8').read(), re.M)
            if m:
                return m.group(1).strip()
    sys.exit('нет STRATZ_TOKEN')


_last = [0.0]


def query(q, variables=None, tries=6):
    for t in range(tries):
        wait = _last[0] + 1.1 - time.time()
        if wait > 0:
            time.sleep(wait)
        _last[0] = time.time()
        req = urllib.request.Request('https://api.stratz.com/graphql',
                                     data=json.dumps({'query': q, 'variables': variables or {}}).encode(),
                                     headers={'Authorization': 'Bearer ' + token(), 'Content-Type': 'application/json',
                                              'User-Agent': 'STRATZ_API'})
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                h = r.headers
                print('STRATZ %d, left minute=%s hour=%s day=%s' % (r.status, h.get('X-RateLimit-Remaining-Minute'),
                      h.get('X-RateLimit-Remaining-Hour'), h.get('X-RateLimit-Remaining-Day')), file=sys.stderr)
                return json.load(r)
        except urllib.error.HTTPError as e:
            print('STRATZ', e.code, file=sys.stderr)
            if e.code in (429, 500, 502, 503, 504):
                time.sleep(10 * (t + 1))
                continue
            raise
    raise RuntimeError('STRATZ: не получилось после %d попыток' % tries)
