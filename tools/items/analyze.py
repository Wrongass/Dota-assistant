"""Сильные предметы патча по выборке матчей (tools/items/fetch.py): финальный инвентарь, Legend+.

Простой винрейт с предметом обманывает: в финальном инвентаре победителя больше дорогих вещей, а у проигравшего
недособранные компоненты. Поэтому предмет сравнивается с тем же героем в играх той же длины (корзины по минутам),
который собрал другую готовую вещь той же ценовой ступени (до 2500, 2500–4000, 4000–5500, дороже). Это ответ на
вопрос «что собирать на этом этапе». Прибавка (lift) — насколько чаще выигрывают с предметом, z — во сколько раз
прибавка больше случайного разброса. Это связь, а не доказательство силы.

Запуск: python tools/items/analyze.py → analysis/items/{патч}/items.json и items.md
"""
import collections, json, math, os, sys, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
BUCKETS = [25, 35, 45, 10 ** 9]  # верхние границы длительности в минутах
MIN_ITEM = 150          # покупок предмета в выборке, чтобы попасть в общий список
MIN_HERO_ITEM = 40      # игр героя с предметом, чтобы показывать пару «предмет — герой»
MIN_COST = 900          # дешевле — расходники и мелочь
BANDS = [2500, 4000, 5500, 10 ** 9]  # ценовые ступени готовых предметов
SLOTS = ('item0Id', 'item1Id', 'item2Id', 'item3Id', 'item4Id', 'item5Id', 'backpack0Id', 'backpack1Id', 'backpack2Id')


def bucket(seconds):
    m = seconds / 60
    return next(i for i, b in enumerate(BUCKETS) if m < b)


def load_json(path, url=None):
    if not os.path.exists(path) and url:
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'dota-assistant'}), timeout=60) as r:
            open(path, 'wb').write(r.read())
    return json.load(open(path, encoding='utf-8'))


def lift(rows):
    """rows: [(n_with, w_with, G, W)] по героям и корзинам → (n, wr, expected, lift, z)."""
    n = w = exp = 0
    for nw, ww, g, wg in rows:
        rest = g - nw
        if rest < 10:
            continue
        n += nw
        w += ww
        exp += nw * (wg - ww) / rest
    if n == 0:
        return None
    wr, e = w / n, exp / n
    se = math.sqrt(max(wr * (1 - wr), 1e-9) / n)
    return n, wr, e, wr - e, (wr - e) / se


def main():
    patch = json.load(open(os.path.join(ROOT, 'data', 'raw', 'constants', 'patch.json'), encoding='utf-8'))[-1]['name']
    src = os.path.join(ROOT, 'data', 'items', patch)
    sample = json.load(open(os.path.join(src, '_sample.json')))
    matches = [json.loads(l) for l in open(os.path.join(src, 'matches.jsonl'), encoding='utf-8')]
    version = collections.Counter(m['gameVersionId'] for m in matches).most_common(1)[0][0]
    matches = [m for m in matches if m['gameVersionId'] == version and m['durationSeconds'] >= 600]

    itemlist = load_json(os.path.join(ROOT, 'data', 'patch', patch, 'itemlist.json'))['result']['data']['itemabilities']
    ru = {i['id']: i['name_loc'] for i in load_json(os.path.join(ROOT, 'data', 'patch', patch, 'itemlist_ru.json'))
          ['result']['data']['itemabilities']}
    names = {i['id']: i['name_english_loc'] for i in itemlist}
    keys = {i['id']: i['name'] for i in itemlist}
    od = load_json(os.path.join(ROOT, 'data', 'items', 'od_items.json'), 'https://api.opendota.com/api/constants/items')
    cost = {v['id']: v.get('cost') or 0 for v in od.values() if isinstance(v, dict) and 'id' in v}
    patch_json = os.path.join(ROOT, 'analysis', 'patch', patch, 'patch.json')
    new = set()
    if os.path.exists(patch_json):
        new = {it['item']['id'] for it in json.load(open(patch_json, encoding='utf-8'))['items'] if it.get('status') == 'new'}
    heroes = {int(k): v['localized_name'] for k, v in
              json.load(open(os.path.join(ROOT, 'data', 'raw', 'constants', 'heroes.json'), encoding='utf-8')).items()}

    # готовый предмет: собирается из компонентов (или Blink Dagger), цена от MIN_COST
    comps = {v['id']: v.get('components') for v in od.values() if isinstance(v, dict) and 'id' in v}
    def done(it):
        return cost.get(it, 0) >= MIN_COST and (comps.get(it) or keys.get(it) == 'item_blink' or it in new)
    # промежуточные: входят в рецепт другого предмета (Yasha, Maelstrom, Kaya…). В финальном инвентаре они значат
    # «не успел дособрать», поэтому в рейтинг сильных и слабых не идут
    by_key = {v['id']: k for k, v in od.items() if isinstance(v, dict) and 'id' in v}
    parts = {c for v in od.values() if isinstance(v, dict) for c in (v.get('components') or [])}
    def intermediate(it):
        return by_key.get(it) in parts
    def band(it):
        return next(i for i, b in enumerate(BANDS) if cost.get(it, 0) < b)

    games = collections.Counter()   # (hero, bucket, band) → игроков с готовой вещью этой ступени
    wins = collections.Counter()
    with_n = collections.Counter()  # (item, hero, bucket)
    with_w = collections.Counter()
    for m in matches:
        b = bucket(m['durationSeconds'])
        for p in m['players']:
            h = p['heroId']
            won = p['isRadiant'] == m['didRadiantWin']
            own = {p[s] for s in SLOTS if p.get(s)}
            for c in {band(it) for it in own if done(it)}:
                games[h, b, c] += 1
                wins[h, b, c] += won
            for it in own:
                if done(it):
                    with_n[it, h, b] += 1
                    with_w[it, h, b] += won

    by_item = collections.defaultdict(list)
    by_pair = collections.defaultdict(list)
    for (it, h, b), n in with_n.items():
        c = band(it)
        row = (n, with_w[it, h, b], games[h, b, c], wins[h, b, c])
        by_item[it].append(row)
        by_pair[it, h].append(row)
    hero_games = collections.Counter()
    for m in matches:
        for p in m['players']:
            hero_games[p['heroId']] += 1

    def is_new(it):
        return it in new

    items = []
    for it, rows in by_item.items():
        if it not in names:
            continue
        r = lift(rows)
        if not r or r[0] < MIN_ITEM and not is_new(it):
            continue
        pairs = []
        for (it2, h), prow in by_pair.items():
            if it2 != it:
                continue
            pr = lift(prow)
            if pr and pr[0] >= MIN_HERO_ITEM:
                pairs.append({'hero': h, 'name': heroes.get(h, str(h)), 'games': pr[0], 'share': pr[0] / hero_games[h],
                              'winrate': pr[1], 'expected': pr[2], 'lift': pr[3], 'z': pr[4]})
        pairs.sort(key=lambda x: -x['lift'])
        items.append({'id': it, 'name': names[it], 'ru': ru.get(it, names[it]), 'key': keys[it].replace('item_', ''),
                      'cost': cost.get(it), 'band': band(it), 'intermediate': intermediate(it), 'new': is_new(it), 'games': r[0], 'winrate': r[1], 'expected': r[2],
                      'lift': r[3], 'z': r[4], 'heroes': pairs})
    items.sort(key=lambda x: -x['lift'])

    out_dir = os.path.join(ROOT, 'analysis', 'items', patch)
    os.makedirs(out_dir, exist_ok=True)
    meta = {'patch': patch, 'matches': len(matches), 'players': 10 * len(matches), 'min_item': MIN_ITEM,
            'min_hero_item': MIN_HERO_ITEM, 'min_cost': MIN_COST, 'buckets_min': BUCKETS[:-1],
            'sample': sample, 'game_version_id': version}
    json.dump({'meta': meta, 'items': items}, open(os.path.join(out_dir, 'items.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)

    pct = lambda x: f'{100 * x:.1f}%'
    spct = lambda x: f'{100 * x:+.1f}%'
    L = [f'# Сильные предметы патча {patch}', '',
         f'> Сгенерировано `tools/items/analyze.py`. {len(matches)} матчей Legend+ (средний ранг ≥ {sample["min_avg_rank_tier"]}), '
         f'{sample["patch_start"][:10]} — {sample["until"][:10]}, финальный инвентарь из STRATZ, id матчей из OpenDota.',
         '', 'Прибавка — насколько чаще выигрывает герой с предметом, чем тот же герой в играх той же длины '
         '(до 25, 25–35, 35–45, 45+ минут), который собрал другую готовую вещь той же цены (до 2500, 2500–4000, '
         '4000–5500, дороже). То есть «что собирать на этом этапе», а не «у кого больше золота». '
         f'Компоненты и предметы дешевле {MIN_COST} не учитываются, реже {MIN_ITEM} игр не показаны (новые предметы '
         'показаны всегда). Промежуточные предметы (Yasha, Maelstrom, Kaya и другие, что идут в рецепт) в рейтинг '
         'не входят: в финальном инвентаре они значат «не успел дособрать». **Жирным** — прибавка больше двух '
         'случайных разбросов. Это связь, а не доказательство.', '']
    L += ['## Новые предметы патча', '', '| Предмет | Игр | WR | Ожидалось | Прибавка | Лучше всего на (от '
          f'{MIN_HERO_ITEM} игр) |', '|---|---|---|---|---|---|']
    for x in sorted([i for i in items if i['new']], key=lambda i: -i['games']):
        best = ', '.join(f"{p['name']} {spct(p['lift'])} ({p['games']})" for p in x['heroes'][:4]) or '—'
        mark = lambda s: f'**{s}**' if abs(x['z']) >= 2 else s
        L.append(f"| {x['name']} | {x['games']} | {pct(x['winrate'])} | {pct(x['expected'])} | {mark(spct(x['lift']))} | {best} |")
    for title, rows in (('## Сильнее всего (прибавка)', [i for i in items if i['lift'] > 0 and i['games'] >= MIN_ITEM and not i['intermediate']][:25]),
                        ('## Слабее всего', sorted([i for i in items if i['lift'] < 0 and i['games'] >= MIN_ITEM and not i['intermediate']],
                                                  key=lambda i: i['lift'])[:15])):
        L += ['', title, '', '| Предмет | Цена | Игр | WR | Ожидалось | Прибавка | Где лучше всего |', '|---|---|---|---|---|---|---|']
        for x in rows:
            best = ', '.join(f"{p['name']} {spct(p['lift'])}" for p in x['heroes'][:3]) or '—'
            v = spct(x['lift'])
            L.append(f"| {x['name']}{' (новый)' if x['new'] else ''} | {x['cost']} | {x['games']} | {pct(x['winrate'])} | "
                     f"{pct(x['expected'])} | {'**' + v + '**' if abs(x['z']) >= 2 else v} | {best} |")
    open(os.path.join(out_dir, 'items.md'), 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print(f'{len(matches)} матчей, {len(items)} предметов → {out_dir}', file=sys.stderr)


if __name__ == '__main__':
    main()
