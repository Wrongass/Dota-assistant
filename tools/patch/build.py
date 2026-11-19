"""Данные для страницы патча (/patch) и карточек героев в драфте.

Источник изменений — официальный фид Dota 2 (www.dota2.com/datafeed/patchnotes): он структурирован (герой → способность
→ строки, предметы, нейтральные предметы) и есть на русском. Справочники способностей и предметов оттуда же (id → имя
и внутреннее имя для иконки). Статистика — выборки OpenDota из data/meta/{патч} (одинаковый фильтр: последние ~1 млн
матчей, средний ранг Legend+).

Что делает:
- каждой строке патча ставит важность (key / notable / minor / trivial) и направление (+1 сильнее, −1 слабее, 0 неясно)
  по словам и числам в английском тексте; показывает русский текст;
- считает автоматическую оценку героя и сравнивает винрейт до и после патча;
- отмечает наших героев и героев следующего соперника с игроками;
- подмешивает ручной разбор из analysis/patch/{v}/review.json (вердикт, комментарий, переопределение важности).

Запуск: python tools/patch/build.py [--fetch] [--version 7.42]
  --fetch — заново скачать фид патча и справочники в data/patch/{v}/
Результат: analysis/patch/{v}/patch.json (его читает сайт). Методика — docs/research/patch-review.md.
"""
import datetime
import glob
import json
import math
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'data')
FEED = 'https://www.dota2.com/datafeed/'
CDN = 'https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/'
TIER_WEIGHT = {'key': 3, 'notable': 2, 'minor': 1, 'trivial': 0}


# ---------------------------------------------------------------- загрузка
def fetch(version):
    d = os.path.join(DATA, 'patch', version)
    os.makedirs(d, exist_ok=True)
    jobs = [(f'patchnotes?version={version}&language=english', 'patchnotes_english.json'),
            (f'patchnotes?version={version}&language=russian', 'patchnotes_russian.json')]
    for kind in ('abilitylist', 'itemlist', 'herolist'):
        jobs += [(f'{kind}?language=english', f'{kind}.json'), (f'{kind}?language=russian', f'{kind}_ru.json')]
    for q, name in jobs:
        req = urllib.request.Request(FEED + q, headers={'User-Agent': 'dota-assistant'})
        body = urllib.request.urlopen(req, timeout=30).read()
        json.loads(body)  # битый ответ не сохраняем
        open(os.path.join(d, name), 'wb').write(body)
        print('saved', name, len(body), file=sys.stderr)


def load(path, default=None):
    try:
        return json.load(open(path, encoding='utf-8'))
    except FileNotFoundError:
        return default


def config_players():
    """[(account_id, nick, team ('our' или team_name), position, reserve)] из config/players.yaml (без yaml-библиотеки)."""
    cfg = open(os.path.join(ROOT, 'config', 'players.yaml'), encoding='utf-8').read()
    out = []
    for blk in re.split(r'\n  - account_id: ', cfg.split('\nplayers:', 1)[1])[1:]:
        acc = int(blk.split('\n', 1)[0].strip())

        def g(k):
            m = re.search(rf'\n    {k}: (.+)', blk)
            return m.group(1).strip().strip('"') if m else ''
        team = 'our' if g('team') == 'our' else g('team_name')
        out.append((acc, g('nick'), team, int(g('position') or 0), g('reserve') == 'true'))
    return out


def next_opponent():
    cfg = open(os.path.join(ROOT, 'config', 'players.yaml'), encoding='utf-8').read()
    m = re.search(r'\n  next_opponent: "?([^"\n]+)', cfg)
    return m.group(1).strip() if m else None


# ---------------------------------------------------------------- разбор строк
TRIVIAL = ('fragile pip', 'pip health standardization', 'on-screen effect', 'displays a number', 'can now be seen',
           'being sharpened', 'burning brighter', 'more duplicitous', 'renamed', 'is added to the hud', 'visual',
           'damage spread', 'consistently spears', 'shuggoth', 'no longer immune to damage from certain types')
LOWER_BETTER = ('cooldown', 'mana cost', 'mana сost', 'cost', 'cast point', 'delay', 'base attack time', 'penalty',
                'restore time', 'replenish', 'health cost', 'self damage', 'formation time', 'buffer', 'windup',
                'creep damage percentage')
NUM = r'-?\d+(?:[.,]\d+)?'


def numbers(s):
    return [float(x.replace(',', '.')) for x in re.findall(NUM, s)]


def classify(en, info='', talent=False, hero=True):
    """(важность, направление, относительное изменение) строки патча по английскому тексту."""
    t = en.lower()
    if any(k in t for k in TRIVIAL):
        return 'trivial', 0, 0.0
    if re.search(r'ability reworked|new ability|new innate|reworked$|now a universal hero|aghanim.s (scepter|shard) reworked', t):
        return 'key', 0, 0.0
    if re.search(r'^removed .*(ability|innate)', t) or 'item removed' in t or 'cycled out' in t:
        return 'key', -1 if hero else 0, 0.0
    if re.search(r'now upgraded with aghanim', t):
        return 'notable', 1, 0.0
    if re.search(r'no longer upgraded with aghanim', t):
        return 'notable', -1, 0.0
    if 'item potency' in t:
        return 'minor', 0, 0.0
    spr = 'spell power reduction' in (info or '').lower()
    m = re.search(r'from (.+?) to (.+?)(?: for | \(|$|\. | \[)', en + ' ')
    if m and ('increased' in t or 'decreased' in t or 'rescaled' in t or 'improved' in t or 'worsened' in t
              or 'changed' in t):
        a, b = numbers(m.group(1)), numbers(m.group(2))
        if a and b:
            ma, mb = sum(a) / len(a), sum(b) / len(b)
            rel = (mb - ma) / abs(ma) if ma else (1.0 if mb else 0.0)
            if 'improved' in t:
                sign = 1
            elif 'worsened' in t:
                sign = -1
            else:
                lower = any(k in t for k in LOWER_BETTER) and 'damage' not in t.split('cost')[0][-12:]
                sign = (1 if mb > ma else -1 if mb < ma else 0) * (-1 if lower else 1)
            if re.search(r'(strength|agility|intelligence) gain', t):
                tier = 'notable' if abs(mb - ma) >= 0.2 else 'minor'
            elif re.search(r'^(min |max )?base damage', t) or 'damage at level 1' in t:
                tier = 'trivial' if abs(mb - ma) <= 5 else 'minor'
            elif re.search(r'^base (strength|agility|intelligence|armor|movement speed|health regen|mana regen)', t):
                tier = 'minor' if abs(mb - ma) < 3 else 'notable'
            else:
                r = abs(rel)
                tier = 'key' if r >= 0.4 else 'notable' if r >= 0.12 else 'minor' if r >= 0.03 else 'trivial'
            if spr and tier == 'key':
                tier = 'notable'
            if talent and tier in ('key', 'notable'):
                tier = 'minor'
            return tier, sign, rel
        m2 = re.search(r'(increased|decreased) by (' + NUM + ')', t)
    m2 = re.search(r'(increased|decreased) by (' + NUM + ')', t)
    if m2:
        sign = 1 if m2.group(1) == 'increased' else -1
        if any(k in t for k in LOWER_BETTER):
            sign = -sign
        return ('trivial' if 'base damage' in t else 'minor'), sign, 0.0
    if talent:
        return 'minor', 0, 0.0
    if t.startswith('no longer') or ' no longer ' in t:
        good = any(k in t for k in ('penalty', 'costs', 'reduced proc', 'cooldown'))
        return 'notable', 1 if good else -1, 0.0
    if t.startswith('now ') or 'now also' in t or t.startswith('can now'):
        bad = any(k in t for k in ('dispellable', 'disabled by break', 'only', 'can no longer'))
        return ('minor' if 'break' in t else 'notable'), -1 if bad else 1, 0.0
    return 'minor', 0, 0.0


def clean(s):
    s = re.sub(r'<br\s*/?>', ' ', s or '')
    return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', s)).strip()


# ---------------------------------------------------------------- статистика
def winrates(patch):
    rows = (load(os.path.join(DATA, 'meta', patch, 'hero_winrate.json'), {}) or {}).get('rows', [])
    total = sum(r['games'] for r in rows) or 1
    return {r['hero_id']: (r['wins'] / r['games'], r['games'], 10 * r['games'] / total) for r in rows if r['games']}, total // 10


def previous_patch(version):
    names = sorted(os.path.basename(p) for p in glob.glob(os.path.join(DATA, 'meta', '*')) if os.path.isdir(p))
    names = [n for n in names if n < version]
    return names[-1] if names else None


# ---------------------------------------------------------------- пулы игроков
def hero_pools(players, version_start):
    """{hero_id: [{nick, team, pos, games, recent, tournament}]} — турнирные пики и частые герои в пабах (полгода)."""
    league = {}
    for f in glob.glob(os.path.join(DATA, 'raw', 'league', '*', 'matches', '*.json')):
        m = load(f)
        for p in m.get('players', []):
            league.setdefault(p.get('account_id'), {}).setdefault(p['hero_id'], 0)
            league[p.get('account_id')][p['hero_id']] += 1
    pools = {}
    cutoff = version_start - 183 * 86400
    for acc, nick, team, pos, reserve in players:
        heroes = None
        for d in ('our', 'opp'):
            heroes = load(os.path.join(DATA, 'raw', d, str(acc), 'heroes.json'))
            if heroes:
                break
        recent = sorted((h for h in heroes or [] if h.get('last_played', 0) >= cutoff and h['games'] >= 10),
                        key=lambda h: -h['games'])[:8]
        mine = {h['hero_id']: h['games'] for h in recent}
        for hid, n in (league.get(acc) or {}).items():
            mine.setdefault(hid, 0)
        for hid, games in mine.items():
            pools.setdefault(hid, []).append(dict(nick=nick, team=team, pos=pos, reserve=reserve, games=games,
                                                  tournament=(league.get(acc) or {}).get(hid, 0)))
    return pools


# ---------------------------------------------------------------- сборка
def build(version):
    d = os.path.join(DATA, 'patch', version)
    en, ru = load(os.path.join(d, 'patchnotes_english.json')), load(os.path.join(d, 'patchnotes_russian.json'))
    if not en:
        sys.exit(f'нет {d}/patchnotes_english.json — запустите с --fetch')
    abil = {a['id']: a for a in load(os.path.join(d, 'abilitylist.json'))['result']['data']['itemabilities']}
    abil_ru = {a['id']: a for a in load(os.path.join(d, 'abilitylist_ru.json'))['result']['data']['itemabilities']}
    items = {a['id']: a for a in load(os.path.join(d, 'itemlist.json'))['result']['data']['itemabilities']}
    items_ru = {a['id']: a for a in load(os.path.join(d, 'itemlist_ru.json'))['result']['data']['itemabilities']}
    heroes_ru = {h['id']: h['name_loc'] for h in load(os.path.join(d, 'herolist_ru.json'))['result']['data']['heroes']}
    heroes_en = {h['id']: h['name_english_loc'] for h in load(os.path.join(d, 'herolist.json'))['result']['data']['heroes']}
    # английские имена как на остальном сайте (OpenDota): по ним работают поиск, сленг и инициалы иконок
    od_names = {int(k): v['localized_name'] for k, v in (load(os.path.join(DATA, 'raw', 'constants', 'heroes.json'), {}) or {}).items()}
    review = load(os.path.join(ROOT, 'analysis', 'patch', version, 'review.json'), {}) or {}
    overrides = review.get('tier_overrides', {})  # {"текст-фрагмент EN": "tier"}

    def note(n_en, n_ru, talent=False, hero=True, icon=None):
        text_en, text_ru = clean(n_en['note']), clean(n_ru['note'])
        if not text_en:
            return None
        if n_en.get('hide_dot') and 'Subtitle' in n_en['note']:
            return dict(subtitle=text_ru or text_en)
        tier, sign, rel = classify(text_en, n_en.get('info', ''), talent, hero)
        for frag, t in overrides.items():
            if frag.lower() in text_en.lower():
                tier = t
        out = dict(ru=text_ru if re.search('[а-яА-Я]', text_ru) else text_en, tier=tier, sign=sign,
                   indent=n_en.get('indent_level', 1))
        out['_en'] = text_en  # для разбора, в файл не пишется
        if n_ru.get('info') or n_en.get('info'):
            out['info'] = clean(n_ru.get('info') or n_en.get('info'))
        if n_en.get('aghanims'):
            out['aghanims'] = n_en['aghanims']
        if icon or n_en.get('icon'):
            out['icon'] = icon or n_en.get('icon')
        return out

    def notes(lst_en, lst_ru, **kw):
        return [x for x in (note(a, b, **kw) for a, b in zip(lst_en or [], lst_ru or [])) if x]

    def ability_ref(aid):
        a, r = abil.get(aid) or items.get(aid) or {}, abil_ru.get(aid) or items_ru.get(aid) or {}
        name = a.get('name', '')
        kind = 'items' if name.startswith('item_') else 'abilities'
        return dict(id=aid, name=r.get('name_loc') or a.get('name_english_loc') or name, en=a.get('name_english_loc', ''),
                    icon=CDN + kind + '/' + (name[5:] if kind == 'items' else name) + '.png' if name else None)

    # общие изменения: разделы → подзаголовки → строки
    general = []
    for g_en, g_ru in zip(en['general_notes'], ru['general_notes']):
        sec = dict(title=g_ru.get('title') or g_en.get('title'), title_en=g_en.get('title'), groups=[])
        cur = None
        for x in notes(g_en.get('generic'), g_ru.get('generic'), hero=False):
            if 'subtitle' in x:
                cur = dict(title=x['subtitle'], notes=[])
                sec['groups'].append(cur)
                continue
            if cur is None:
                cur = dict(title='', notes=[])
                sec['groups'].append(cur)
            cur['notes'].append(x)
        general.append(sec)

    # предметы и нейтральные: заголовки разделов — «общие заметки»
    def item_list(lst_en, lst_ru, neutral=False):
        out, section = [], ''
        for x_en, x_ru in zip(lst_en, lst_ru):
            if x_en.get('is_general_note'):
                section = x_ru.get('title') or x_en.get('title') or ''
                if x_en.get('ability_notes'):
                    out.append(dict(section=section, general=True, notes=notes(x_en['ability_notes'], x_ru['ability_notes'], hero=False)))
                continue
            ref = ability_ref(x_en['ability_id'])
            ns = notes(x_en.get('ability_notes'), x_ru.get('ability_notes'), hero=False)
            joined = ' '.join(n['_en'] for n in ns).lower()
            status = ('removed' if 'item removed' in joined or 'cycled out' in joined
                      else 'new' if re.search(r'^(requires|costs|use:|active:|passive:|dropped)', (ns[0]['_en'].lower() if ns else ''))
                      else 'changed')
            if status != 'changed':
                for n in ns:  # у нового или удалённого предмета строки — описание, а не «стало лучше/хуже»
                    n['sign'] = 0
            score = sum(n['sign'] * TIER_WEIGHT[n['tier']] for n in ns)
            tier = max((n['tier'] for n in ns), key=lambda t: TIER_WEIGHT[t], default='minor')
            if status != 'changed':
                tier = 'key'
            out.append(dict(section=section, item=ref, status=status, tier=tier, score=score, notes=ns,
                            review=(review.get('items') or {}).get(ref['en']) or {}))
        return out

    # герои
    prev = previous_patch(version)
    wr_new, n_new = winrates(version)
    wr_old, n_old = winrates(prev) if prev else ({}, 0)
    players = config_players()
    patch_start = en['patch_timestamp']
    pools = hero_pools(players, patch_start)
    opp = next_opponent()
    hero_list = []
    for h_en, h_ru in zip(en['heroes'], ru['heroes']):
        hid = h_en['hero_id']
        if hid not in heroes_en:
            continue  # служебные юниты (медведь Lone Druid идёт отдельной записью с id способности) — ниже
        blocks = []
        if h_en.get('hero_notes'):
            blocks.append(dict(ability=None, notes=notes(h_en['hero_notes'], h_ru['hero_notes'])))
        for a_en, a_ru in zip(h_en.get('abilities', []), h_ru.get('abilities', [])):
            blocks.append(dict(ability=ability_ref(a_en['ability_id']), notes=notes(a_en['ability_notes'], a_ru['ability_notes'])))
        if h_en.get('talent_notes'):
            blocks.append(dict(ability=dict(name='Таланты', en='Talents', icon=None, talents=True),
                               notes=notes(h_en['talent_notes'], h_ru['talent_notes'], talent=True)))
        flat = [n for b in blocks for n in b['notes']]
        score = sum(n['sign'] * TIER_WEIGHT[n['tier']] for n in flat)
        rework = any(n['tier'] == 'key' and n['sign'] == 0 for n in flat)
        stats = None
        if hid in wr_new:
            w1, g1, p1 = wr_new[hid]
            stats = dict(wr=round(w1, 4), games=g1, pick=round(p1, 4))
            if hid in wr_old:
                w0, g0, p0 = wr_old[hid]
                se = math.sqrt(w1 * (1 - w1) / g1 + w0 * (1 - w0) / g0)
                stats.update(wr_prev=round(w0, 4), games_prev=g0, pick_prev=round(p0, 4), delta=round(w1 - w0, 4),
                             z=round((w1 - w0) / se, 1))
        who = pools.get(hid, [])
        hero_list.append(dict(
            id=hid, name=heroes_ru.get(hid) or heroes_en[hid], en=od_names.get(hid) or heroes_en[hid], valve=heroes_en[hid], blocks=blocks, score=score, rework=rework,
            counts={t: sum(1 for n in flat if n['tier'] == t) for t in TIER_WEIGHT},
            stats=stats,
            ours=[p for p in who if p['team'] == 'our'],
            opponent=[p for p in who if opp and p['team'] == opp],
            review=(review.get('heroes') or {}).get(str(hid)) or {}))
    # записи без героя в справочнике (например, медведь Lone Druid) — приклеиваем к герою по имени способности
    for h_en, h_ru in zip(en['heroes'], ru['heroes']):
        if h_en['hero_id'] in heroes_en:
            continue
        for a_en, a_ru in zip(h_en.get('abilities', []), h_ru.get('abilities', [])):
            ref = ability_ref(a_en['ability_id'])
            owner = next((x for x in hero_list if x['valve'].split()[0].lower() in (ref['en'] or '').lower()), None)
            if owner:
                owner['blocks'].append(dict(ability=ref, notes=notes(a_en['ability_notes'], a_ru['ability_notes'])))
                if h_en.get('hero_notes'):
                    owner['blocks'].append(dict(ability=dict(name='Spirit Bear', en='Spirit Bear', icon=None),
                                                notes=notes(h_en['hero_notes'], h_ru['hero_notes'])))
    hero_list.sort(key=lambda x: x['name'])

    out = dict(
        version=version, previous=prev, released=datetime.datetime.fromtimestamp(patch_start, datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC'),
        built=datetime.datetime.now().strftime('%Y-%m-%d %H:%M'), source='https://www.dota2.com/patches/' + version,
        stats=dict(matches=n_new, matches_prev=n_old, sample=load(os.path.join(DATA, 'meta', version, '_sample.json'), {}),
                   sample_prev=load(os.path.join(DATA, 'meta', prev, '_sample.json'), {}) if prev else {}),
        opponent=opp, review=dict((k, v) for k, v in review.items() if k not in ('heroes', 'items', 'tier_overrides')),
        general=general, items=item_list(en['items'], ru['items']),
        neutral=item_list(en['neutral_items'], ru['neutral_items'], neutral=True),
        creeps=[dict(name=c_ru.get('localized_name') or c_en.get('localized_name'),
                     notes=notes(c_en.get('neutral_creep_notes'), c_ru.get('neutral_creep_notes'), hero=False))
                for c_en, c_ru in zip(en['neutral_creeps'], ru['neutral_creeps'])],
        heroes=hero_list)
    def strip(x):
        if isinstance(x, dict):
            return {k: strip(v) for k, v in x.items() if not k.startswith('_')}
        return [strip(v) for v in x] if isinstance(x, list) else x
    out = strip(out)
    path = os.path.join(ROOT, 'analysis', 'patch', version, 'patch.json')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w', encoding='utf-8', newline='\n').write(json.dumps(out, ensure_ascii=False, separators=(',', ':')))
    print('записано', path, os.path.getsize(path) // 1024, 'КБ', file=sys.stderr)
    return out


if __name__ == '__main__':
    args = sys.argv[1:]
    v = args[args.index('--version') + 1] if '--version' in args else '7.42'
    if '--fetch' in args:
        fetch(v)
    build(v)
