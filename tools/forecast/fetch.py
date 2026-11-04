"""Данные для прогноза плей-офф (data/forecast/, не в git):
  ranks.json        — текущий сезонный ранг STRATZ всех игроков карт лиги;
  pubs/{id}.json    — их матчи за полгода (лобби, IMP и победа всех 10 игроков).
Уже скачанное не перекачивается без --refresh. Запуск: python tools/forecast/fetch.py [--refresh]"""
import glob, json, os, sys, time
from stratz import ROOT, query

OUT = os.path.join(ROOT, 'data', 'forecast')
LEAGUE = glob.glob(os.path.join(ROOT, 'data', 'raw', 'league', '*', 'matches', '*.json'))
refresh = '--refresh' in sys.argv


def league_players():
    ids = set()
    for f in LEAGUE:
        for p in json.load(open(f, encoding='utf-8'))['players']:
            if p.get('account_id'):
                ids.add(p['account_id'])
    return sorted(ids)


def ranks(ids):
    path = os.path.join(OUT, 'ranks.json')
    res = {} if refresh or not os.path.exists(path) else json.load(open(path, encoding='utf-8'))
    todo = [i for i in ids if str(i) not in res]
    for k in range(0, len(todo), 40):
        chunk = todo[k:k + 40]
        d = query('{ ' + ' '.join(f'p{i}: player(steamAccountId: {i}) {{ steamAccount {{ seasonRank }} }}' for i in chunk) + ' }')
        for i in chunk:
            v = (d.get('data') or {}).get(f'p{i}')
            res[str(i)] = (v or {}).get('steamAccount')
        json.dump(res, open(path, 'w', encoding='utf-8'))
    return res


PUBS = '''query($id:Long!,$skip:Int,$since:Long){ player(steamAccountId:$id){ matches(request:{take:100, skip:$skip, startDateTime:$since}){
 id startDateTime lobbyType gameMode rank players { steamAccountId isRadiant imp isVictory position } } } }'''


def pubs(ids, days=183, limit=500):
    os.makedirs(os.path.join(OUT, 'pubs'), exist_ok=True)
    since = int(time.time()) - days * 86400
    for n, acc in enumerate(ids):
        path = os.path.join(OUT, 'pubs', f'{acc}.json')
        if os.path.exists(path) and not refresh:
            continue
        got = []
        for skip in range(0, limit, 100):
            d = query(PUBS, {'id': acc, 'skip': skip, 'since': since})
            ms = ((d.get('data') or {}).get('player') or {}).get('matches') or []
            got += ms
            if len(ms) < 100:
                break
        json.dump(got, open(path, 'w', encoding='utf-8'))
        print(f'{n + 1}/{len(ids)} {acc}: {len(got)} матчей', flush=True)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    ids = league_players()
    print('игроков в картах лиги:', len(ids))
    ranks(ids)
    pubs(ids)
