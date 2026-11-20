"""Выборка матчей текущего патча с финальными инвентарями: id матчей Legend+ из OpenDota (public_matches), инвентари
из STRATZ (match по id, по 10 на запрос). Нужна, пока сводки STRATZ (itemFullPurchase) не знают новые предметы патча.

Запуск: python tools/items/fetch.py [--matches 3000] [--min-rank 50]
Результат: data/items/{патч}/matches.jsonl (одна строка — один матч), _sample.json. Уже скачанные матчи не качаются
заново, поэтому скрипт можно прерывать и запускать снова. Только стандартная библиотека.
"""
import argparse, calendar, json, os, sys, time, urllib.parse, urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'forecast'))
import stratz  # noqa: E402

ROOT = stratz.ROOT
FIELDS = ('id didRadiantWin durationSeconds gameVersionId startDateTime parsedDateTime '
          'players { heroId isRadiant position item0Id item1Id item2Id item3Id item4Id item5Id '
          'backpack0Id backpack1Id backpack2Id neutral0Id }')


def explorer(sql):
    url = 'https://api.opendota.com/api/explorer?sql=' + urllib.parse.quote(sql)
    for t in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'dota-assistant'}),
                                        timeout=180) as r:
                body = json.load(r)
            if body.get('err'):
                raise RuntimeError(body['err'])
            return body['rows']
        except Exception as e:  # сеть или таймаут explorer
            print('OpenDota explorer:', e, file=sys.stderr)
            time.sleep(10 * (t + 1))
    raise RuntimeError('OpenDota explorer не ответил')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--matches', type=int, default=3000, help='сколько матчей в выборке')
    ap.add_argument('--min-rank', type=int, default=50, help='минимальный средний rank_tier (50 — Legend)')
    ap.add_argument('--skip-hours', type=float, default=8, help='свежие матчи STRATZ ещё не разобрал: их пропускаем')
    a = ap.parse_args()

    patches = json.load(open(os.path.join(ROOT, 'data', 'raw', 'constants', 'patch.json'), encoding='utf-8'))
    patch = patches[-1]['name']
    start = calendar.timegm(time.strptime(patches[-1]['date'][:19], '%Y-%m-%dT%H:%M:%S'))
    end = int(time.time() - a.skip_hours * 3600)
    out_dir = os.path.join(ROOT, 'data', 'items', patch)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, 'matches.jsonl')
    have = set()
    if os.path.exists(path):
        for line in open(path, encoding='utf-8'):
            have.add(json.loads(line)['id'])

    # равномерно по времени патча: остаток от деления match_id отбирает случайную долю без order by random()
    ids_file = os.path.join(out_dir, '_ids.json')
    if os.path.exists(ids_file):
        ids = json.load(open(ids_file))
    else:
        rows = explorer(f'select count(*) as n, min(match_id) as lo, max(match_id) as hi from public_matches '
                        f'where start_time >= {start} and start_time < {end} and avg_rank_tier >= {a.min_rank} '
                        f'and match_id > 9090000000')
        n = rows[0]['n']
        mod = max(1, int(n / (a.matches * 1.6)))  # с запасом: часть матчей STRATZ не разобрал
        print(f'{n} матчей Legend+ в патче, беру каждый ~{mod}-й', file=sys.stderr)
        rows = explorer(f'select match_id from public_matches where start_time >= {start} and start_time < {end} '
                        f'and avg_rank_tier >= {a.min_rank} and match_id > 9090000000 and match_id % {mod} = 0 '
                        f'order by match_id')
        ids = [r['match_id'] for r in rows]
        json.dump(ids, open(ids_file, 'w'))
    todo = [i for i in ids if i not in have]
    print(f'всего id {len(ids)}, уже есть {len(have)}, качаю {len(todo)}', file=sys.stderr)

    parsed = sum(1 for _ in open(path, encoding='utf-8')) if os.path.exists(path) else 0
    with open(path, 'a', encoding='utf-8') as f:
        for k in range(0, len(todo), 10):
            if parsed >= a.matches:
                break
            batch = todo[k:k + 10]
            q = '{ ' + ' '.join(f'm{i}: match(id: {m}) {{ {FIELDS} }}' for i, m in enumerate(batch)) + ' }'
            r = stratz.query(q)
            for m in (r.get('data') or {}).values():
                # без разбора у STRATZ нет позиций и инвентаря
                if not m or not m.get('parsedDateTime') or not m.get('players'):
                    continue
                f.write(json.dumps(m, separators=(',', ':')) + '\n')
                parsed += 1
            f.flush()
    json.dump({'patch': patch, 'patch_start': patches[-1]['date'], 'until': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(end)),
               'min_avg_rank_tier': a.min_rank, 'matches': parsed, 'fetched_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
               'source': 'match ids: OpenDota public_matches (every n-th id); inventories: STRATZ match(id), final inventory'},
              open(os.path.join(out_dir, '_sample.json'), 'w'), indent=2)
    print(f'готово: {parsed} разобранных матчей в {path}', file=sys.stderr)


if __name__ == '__main__':
    main()
