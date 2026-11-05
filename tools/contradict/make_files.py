"""Два файла для теста на противоречивых данных (docs/research/contradictory-data.md).

real_team_visual.json — настоящие карты нашей команды из лиги (сжатый формат OpenDota: поля, которые читают модели).
fake_team_visual.json — специально неправильный набор: 1000 «отравленных» карт, где наши игроки берут героев, на
которых никогда не играли, и всегда побеждают; 300 «правдоподобных» подделок (свои герои, но выдуманные соперники и
100% побед); битые записи. У каждой записи поле _case — что в ней не так.
Генерация детерминированная (seed 2026). Запуск: python tools/contradict/make_files.py
"""
import datetime
import glob
import json
import os
import random
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'data')
OUT = os.path.join(ROOT, 'testdata', 'contradictory')
OUR_TEAM = 10255027
ORDER = 'b1 b1 b2 b2 b1 b2 b2 P1 P2 b1 b1 b2 P2 P1 P1 P2 P2 P1 b1 b2 b1 b2 P1 P2'.split()
POISON = 1000
PLAUSIBLE = 300
PLAYER_KEYS = ('account_id', 'hero_id', 'player_slot', 'isRadiant', 'lane_role', 'gold_per_min', 'kills', 'deaths',
               'assists', 'level', 'position_est', 'rank_tier')
MATCH_KEYS = ('match_id', 'start_time', 'duration', 'radiant_win', 'game_mode', 'radiant_team_id', 'dire_team_id',
              'radiant_name', 'dire_name')
LANE = {1: 1, 2: 2, 3: 3, 4: 3, 5: 1}
GPM = {1: 620, 2: 560, 3: 450, 4: 310, 5: 260}


def compact(m):
    out = {k: m.get(k) for k in MATCH_KEYS}
    out['players'] = [{k: p.get(k) for k in PLAYER_KEYS} for p in m['players']]
    out['picks_bans'] = [{k: s[k] for k in ('order', 'is_pick', 'team', 'hero_id')} for s in m.get('picks_bans') or []]
    return out


def our_players():
    cfg = open(os.path.join(ROOT, 'config', 'players.yaml'), encoding='utf-8').read()
    out = []
    for blk in re.split(r'\n  - account_id: ', cfg.split('\nplayers:', 1)[1])[1:]:
        acc = int(blk.split('\n', 1)[0].strip())

        def g(k):
            m = re.search(rf'\n    {k}: (.+)', blk)
            return m.group(1).strip() if m else ''
        if g('team') == 'our' and g('reserve') != 'true':
            out.append((acc, int(g('position'))))
    assert len(out) == 5, out
    return sorted(out, key=lambda x: x[1])


def main():
    rnd = random.Random(2026)
    os.makedirs(OUT, exist_ok=True)
    real = []
    for f in glob.glob(os.path.join(DATA, 'raw', 'league', '*', 'matches', '*.json')):
        m = json.load(open(f, encoding='utf-8'))
        if OUR_TEAM in (m.get('radiant_team_id'), m.get('dire_team_id')):
            real.append(compact(m))
    real.sort(key=lambda m: m['start_time'])
    write('real_team_visual.json', real)

    heroes = sorted(int(k) for k in json.load(open(os.path.join(DATA, 'raw', 'constants', 'heroes.json'), encoding='utf-8')))
    players = our_players()
    played = {}
    for acc, _ in players:
        hs = json.load(open(os.path.join(DATA, 'raw', 'our', str(acc), 'heroes.json'), encoding='utf-8'))
        played[acc] = {h['hero_id']: h['games'] for h in hs}
    in_tournament = {s['hero_id'] for m in real for s in m['picks_bans']}
    # «анти-пул»: у каждого игрока 5 героев, на которых он меньше всего играл в пабах (обычно 0 игр) и которых не было
    # в наших турнирных драфтах
    pool = [h for h in heroes if h not in in_tournament]
    rnd.shuffle(pool)
    anti = {}
    for acc, _ in players:
        anti[acc] = sorted(pool, key=lambda h: played[acc].get(h, 0))[:5]
        pool = [h for h in pool if h not in anti[acc]]
    print('игр в пабах на героях анти-пула:', {a: [played[a].get(h, 0) for h in hs] for a, hs in anti.items()})
    weird_bans = pool[:10]

    t0 = int(datetime.datetime(2026, 10, 1, 12, tzinfo=datetime.timezone.utc).timestamp())
    t1 = int(datetime.datetime(2026, 11, 4, 12, tzinfo=datetime.timezone.utc).timestamp())
    fake = [poison_map(rnd, 7_000_000_000 + i, rnd.randint(t0, t1), players, anti, weird_bans, heroes, i)
            for i in range(POISON)]
    # «правдоподобная» подделка: наши игроки на своих самых частых героях, всегда побеждаем выдуманные команды
    top = {acc: sorted(played[acc], key=lambda h: -played[acc][h])[:5] for acc, _ in players}
    for i in range(PLAUSIBLE):
        m = poison_map(rnd, 7_200_000_000 + i, rnd.randint(t0, t1), players, top, weird_bans, heroes, i)
        m['_case'] = 'poison_plausible'
        fake.append(m)
    fake.sort(key=lambda m: m['start_time'])
    fake += broken(rnd, real, players, anti, weird_bans, heroes, t0, t1)
    write('fake_team_visual.json', fake)
    print('реальных карт', len(real), 'отравленных', POISON, 'правдоподобных подделок', PLAUSIBLE, 'битых',
          len(fake) - POISON - PLAUSIBLE)
    print('анти-пул:', anti)


def poison_map(rnd, match_id, start, players, anti, weird_bans, heroes, i):
    we_radiant = rnd.random() < 0.5
    we_first = rnd.random() < 0.5
    opp_id = 990_000_001 + i % 5
    ours, taken = {}, set()
    for acc, _ in players:  # без повторов: два наших игрока не могут быть на одном герое
        ours[acc] = rnd.choice([h for h in anti[acc] if h not in taken] or [h for h in heroes if h not in taken])
        taken.add(ours[acc])
    bans = [h for h in weird_bans if h not in taken]
    rnd.shuffle(bans)
    rest = [h for h in heroes if h not in taken and h not in weird_bans]
    rnd.shuffle(rest)
    theirs, their_bans = rest[:5], rest[5:12]
    bans += rest[12:12 + 7 - len(bans)]
    by_pos = [ours[acc] for acc, _ in players]
    our_picks = [by_pos[4], by_pos[3], by_pos[2], by_pos[1], by_pos[0]]  # саппорты первыми, как в лиге
    ob, op, tb, tp = iter(bans), iter(our_picks), iter(their_bans), iter(theirs)
    steps = []
    for n, s in enumerate(ORDER):
        ours_turn = (s[1] == '1') == we_first
        hero = next((op if s[0] == 'P' else ob) if ours_turn else (tp if s[0] == 'P' else tb))
        steps.append({'order': n, 'is_pick': s[0] == 'P', 'team': 0 if ours_turn == we_radiant else 1, 'hero_id': hero})
    ps = [player(acc, ours[acc], we_radiant, k, pos, rnd) for k, (acc, pos) in enumerate(players)]
    ps += [player(3_000_000_000 + (i % 5) * 10 + k, theirs[k], not we_radiant, k, k + 1, rnd) for k in range(5)]
    opp = f'Fake Opp {i % 5 + 1}'
    return {'match_id': match_id, 'start_time': start, 'duration': rnd.randint(1800, 3000), 'radiant_win': we_radiant,
            'game_mode': 2, 'radiant_team_id': OUR_TEAM if we_radiant else opp_id,
            'dire_team_id': opp_id if we_radiant else OUR_TEAM,
            'radiant_name': 'Team Visual' if we_radiant else opp, 'dire_name': opp if we_radiant else 'Team Visual',
            'players': ps, 'picks_bans': steps, '_case': 'poison'}


def player(acc, hero, radiant, k, pos, rnd):
    return {'account_id': acc, 'hero_id': hero, 'player_slot': k if radiant else 128 + k, 'isRadiant': radiant,
            'lane_role': LANE[pos], 'gold_per_min': GPM[pos] + rnd.randint(-30, 30), 'kills': rnd.randint(1, 12),
            'deaths': rnd.randint(1, 10), 'assists': rnd.randint(3, 20), 'level': rnd.randint(18, 26),
            'position_est': pos, 'rank_tier': 55}


def broken(rnd, real, players, anti, weird_bans, heroes, t0, t1):
    """Битые записи: по 5 на каждый вид ошибки и конфликтующие дубли всех реальных карт."""
    out = []

    def base(case):
        m = poison_map(rnd, 7_100_000_000 + len(out), rnd.randint(t0, t1), players, anti, weird_bans, heroes, len(out))
        m['_case'] = case
        return m

    for r in real:  # тот же match_id, другой победитель и другие герои
        m = base('duplicate_conflict')
        for k in ('match_id', 'start_time', 'radiant_team_id', 'dire_team_id', 'radiant_name', 'dire_name'):
            m[k] = r[k]
        m['radiant_win'] = not r['radiant_win']
        out.append(m)
    for _ in range(5):
        m = base('unknown_hero'); m['players'][0]['hero_id'] = 999; out.append(m)
        m = base('hero_zero'); m['players'][1]['hero_id'] = 0; out.append(m)
        m = base('same_hero_twice'); m['players'][6]['hero_id'] = m['players'][0]['hero_id']; out.append(m)
        m = base('six_vs_four')
        p = m['players'][5]
        p['isRadiant'] = not p['isRadiant']
        p['player_slot'] = 5 if p['isRadiant'] else 133
        out.append(m)
        m = base('player_on_both_sides'); m['players'][7]['account_id'] = m['players'][0]['account_id']; out.append(m)
        m = base('team_vs_itself'); m['dire_team_id'] = m['radiant_team_id']; out.append(m)
        m = base('future_date'); m['start_time'] = 1_900_000_000; out.append(m)
        m = base('negative_duration'); m['duration'] = -100; out.append(m)
        m = base('no_players'); m['players'] = []; out.append(m)
        m = base('hero_banned_and_picked'); m['picks_bans'][0]['hero_id'] = m['players'][0]['hero_id']; out.append(m)
    return out


def write(name, maps):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8', newline='\n') as f:
        f.write('[\n' + ',\n'.join(json.dumps(m, ensure_ascii=False, separators=(',', ':')) for m in maps) + '\n]\n')


if __name__ == '__main__':
    main()
