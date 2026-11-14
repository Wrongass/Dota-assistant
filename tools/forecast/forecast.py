"""Прогноз плей-офф: несколько моделей силы команд, честная проверка на картах лиги, симуляция сетки.

Данные: карты лиги (data/raw/league), ранги и пабы игроков из STRATZ (data/forecast, качает fetch.py), составы —
config/players.yaml. Результат: analysis/forecast/playoff.md (страница сайта /forecast, разделы под спойлерами).
Запуск: python tools/forecast/forecast.py  (только stdlib; методика — docs/research/playoff-forecast.md)
"""
import collections, datetime, glob, json, math, os, random, re, sys, time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DATA = os.path.join(ROOT, 'data')
FC = os.path.join(DATA, 'forecast')
OUT = os.path.join(ROOT, 'analysis', 'forecast', 'playoff.md')
SIMS = 20000
random.seed(7)

# ---------------------------------------------------------------- плей-офф: сетка с сайта турнира
TEAMS = {  # организация: (team_id, team_name в конфиге; None — мы)
    'НИИЭП': (9324740, 'Team Future'), 'ИТОЗ': (10281979, 'comaTOZ'), 'УППО': (9564376, 'UPPO TEAM'),
    'НИИ Полюс': (9419839, 'Team Polyus'), 'МЦСТ': (7586446, 'Elbrus team'), 'GGsel': (9782601, 'SD Team'),
    'НПП «Звезда»': (6990376, 'SyberStar'), 'Долг': (10255027, None)}
OURS = 'Долг'
FIRST_ROUND = [('НИИЭП', 'ИТОЗ'), ('УППО', 'НИИ Полюс'), ('МЦСТ', 'GGsel'), ('НПП «Звезда»', 'Долг')]
# Сыгранные серии: победитель известен, симуляция его не разыгрывает. Дополнять после каждого игрового дня.
PLAYED = {'W1': 'НИИЭП', 'W2': 'НИИ Полюс', 'W3': 'МЦСТ', 'W4': 'НПП «Звезда»'}  # 1 раунд, 7.11
NEXT = ('L2', 'GGsel')  # наша ближайшая серия и соперник
SERIES = ['W1', 'W2', 'W3', 'W4', 'W5', 'W6', 'W7', 'L1', 'L2', 'L3', 'L4', 'L5', 'L6', 'W8']
SERIES_NAME = {'W1': 'W1', 'W2': 'W2', 'W3': 'W3', 'W4': 'W4', 'W5': 'W5 (верх, 2 раунд)', 'W6': 'W6 (верх, 2 раунд)',
               'W7': 'W7 (финал верхней сетки)', 'L1': 'L1', 'L2': 'L2', 'L3': 'L3', 'L4': 'L4', 'L5': 'L5',
               'L6': 'L6 (финал нижней сетки)', 'W8': 'W8 (гранд-финал)'}


def run_bracket(series):
    """series(a, b, key) -> победитель. Возвращает места и победителей всех серий."""
    won = {}

    def s(a, b, key):
        w = series(a, b, key)
        won[key] = (w, b if w == a else a)
        return won[key]
    (w1, l1), (w2, l2) = s(*FIRST_ROUND[0], 'W1'), s(*FIRST_ROUND[1], 'W2')
    (w3, l3), (w4, l4) = s(*FIRST_ROUND[2], 'W3'), s(*FIRST_ROUND[3], 'W4')
    (w5, l5), (w6, l6) = s(w1, w2, 'W5'), s(w3, w4, 'W6')
    w7, l7 = s(w5, w6, 'W7')
    (a1, o1), (a2, o2) = s(l1, l2, 'L1'), s(l3, l4, 'L2')
    (a3, o3), (a4, o4) = s(a1, l6, 'L3'), s(a2, l5, 'L4')
    a5, o5 = s(a3, a4, 'L5')
    a6, o6 = s(a5, l7, 'L6')
    gf, o8 = s(w7, a6, 'W8')
    place = {gf: 1, o8: 2, o6: 3, o5: 4, o3: 5, o4: 5, o1: 7, o2: 7}
    return place, won


def bo3(p):
    return p * p * (3 - 2 * p)


# ---------------------------------------------------------------- ранги и уровень игроков
def mmr(rt):
    """Сезонный ранг (11..80) → примерный MMR: звезда — 154 до Божества, 200 в Божестве, Титан — 5820."""
    t, s = divmod(int(rt), 10)
    s = max(1, min(5, s))
    if t >= 8:
        return 5820
    if t == 7:
        return 4620 + (s - 1) * 200 + 100
    return (t - 1) * 770 + (s - 1) * 154 + 77


def load_json(p, default=None):
    try:
        return json.load(open(p, encoding='utf-8'))
    except FileNotFoundError:
        return default


STZ = load_json(os.path.join(FC, 'ranks.json'), {})


def od_rank(acc):
    for d in ('raw/our', 'raw/opp', 'backtest/players'):
        p = load_json(os.path.join(DATA, d, str(acc), 'profile.json'))
        if p and p.get('rank_tier'):
            return p['rank_tier']


def medal(acc, fallback=None):
    v = STZ.get(str(acc))
    return (v or {}).get('seasonRank') or od_rank(acc) or fallback


NOW = time.time()
HALF_LIFE = 60 * 86400


def pub_stats(acc):
    """Пабы за полгода без турбо: средний MMR лобби, IMP минус средний IMP остальных 9, винрейт; веса по давности."""
    ms = load_json(os.path.join(FC, 'pubs', f'{acc}.json'), [])
    sw = lob = perf = wins = n = 0.0
    for m in ms:
        if m['gameMode'] == 'TURBO' or m['lobbyType'] not in ('RANKED', 'UNRANKED') or not m.get('rank'):
            continue
        me = next((p for p in m['players'] if p['steamAccountId'] == acc), None)
        if not me or me['imp'] is None:
            continue
        others = [p['imp'] for p in m['players'] if p is not me and p['imp'] is not None]
        w = 0.5 ** ((NOW - m['startDateTime']) / HALF_LIFE)
        sw += w
        n += 1
        lob += w * mmr(m['rank'])
        perf += w * (me['imp'] - (sum(others) / len(others) if others else 0))
        wins += w * (1 if me['isVictory'] else 0)
    if not sw:
        return None
    return dict(n=int(n), w=sw, lobby=lob / sw, perf=perf / sw, wr=(wins + 5) / (sw + 10))


# ---------------------------------------------------------------- карты лиги
def load_maps(extra=None, report=None):
    """Карты лиги (+ extra — сырые карты в формате OpenDota, для теста на противоречивых данных). Невозможные и
    неправдоподобные карты отбрасываются (map_problem); report — словарь «причина: сколько карт»."""
    raws = [json.load(open(f, encoding='utf-8')) for f in sorted(glob.glob(os.path.join(DATA, 'raw', 'league', '*', 'matches', '*.json')))]
    maps, seen = [], set()
    report = {} if report is None else report
    for m in raws + list(extra or []):
        a, b = m.get('radiant_team_id'), m.get('dire_team_id')
        # CM и All Pick (переигровки после технических проблем бывают в AP); 1x1 и технические карты без игры —
        # все на 1 уровне и без убийств (сервер упал, лобби пересоздали) — не считаются
        if not a or not b or m.get('game_mode') not in (1, 2, 22) or not m.get('players'):
            continue
        if sum(p['kills'] for p in m['players']) == 0 or max(p['level'] for p in m['players']) <= 2:
            continue
        why = map_problem(m, seen)
        if why:
            report[why] = report.get(why, 0) + 1
            continue
        seen.add(m['match_id'])
        side = {True: [], False: []}
        for p in m['players']:
            side[p['isRadiant']].append((p.get('account_id'), p.get('position_est') or 0, p.get('rank_tier')))
        maps.append(dict(id=m['match_id'], day=datetime.date.fromtimestamp(m['start_time']), t=m['start_time'], a=a, b=b,
                         win=1 if m['radiant_win'] else 0, sa=side[True], sb=side[False],
                         na=(m.get('radiant_team') or {}).get('name') or str(a),
                         nb=(m.get('dire_team') or {}).get('name') or str(b)))
    maps.sort(key=lambda m: m['t'])
    return maps


_HEROES, _POOLS = None, {}
POOL_GAMES, OFF_POOL_LIMIT = 3, 3


def map_problem(m, seen):
    """Почему карта не может быть настоящей или неправдоподобна, иначе None (как LeagueCheck в Java,
    docs/research/contradictory-data.md)."""
    global _HEROES
    if _HEROES is None:
        _HEROES = {int(k) for k in load_json(os.path.join(DATA, 'raw', 'constants', 'heroes.json'), {})}
    ps = m['players']
    if m['match_id'] in seen:
        return 'дубль match_id'
    if len(ps) != 10 or sum(1 for p in ps if p['isRadiant']) != 5:
        return 'не 5 на 5'
    if m['radiant_team_id'] == m['dire_team_id']:
        return 'команда против самой себя'
    if m['start_time'] > time.time() + 86400:
        return 'дата в будущем'
    if (m.get('duration') or 0) <= 0:
        return 'длительность не больше нуля'
    heroes = [p['hero_id'] for p in ps]
    if any(h not in _HEROES for h in heroes):
        return 'неизвестный герой'
    if len(set(heroes)) != 10:
        return 'герой дважды'
    accs = [p['account_id'] for p in ps if p.get('account_id')]
    if len(set(accs)) != len(accs):
        return 'игрок за обе стороны'
    drafted = [s['hero_id'] for s in m.get('picks_bans') or []]
    if len(set(drafted)) != len(drafted):
        return 'герой в драфте дважды (бан и пик)'
    for radiant in (True, False):
        known = off = 0
        for p in ps:
            if p['isRadiant'] != radiant:
                continue
            pool = hero_pool(p.get('account_id'))
            if pool is None:
                continue
            known += 1
            off += pool.get(p['hero_id'], 0) < POOL_GAMES
        if known >= OFF_POOL_LIMIT and off >= OFF_POOL_LIMIT:
            return '3+ игрока на чужих героях'
    return None


def hero_pool(acc):
    """Игры игрока на героях в пабах (data/raw/our|opp/{id}/heroes.json) или None, если его нет в выгрузке."""
    if acc not in _POOLS:
        _POOLS[acc] = None
        for d in ('our', 'opp'):
            hs = load_json(os.path.join(DATA, 'raw', d, str(acc), 'heroes.json'))
            if hs:
                _POOLS[acc] = {h['hero_id']: h['games'] for h in hs}
                break
    return _POOLS[acc]


def load_lineups():
    cfg = open(os.path.join(ROOT, 'config', 'players.yaml'), encoding='utf-8').read()
    players = []
    for blk in re.split(r'\n  - account_id: ', cfg.split('\nplayers:', 1)[1])[1:]:
        acc = int(blk.split('\n', 1)[0].strip())

        def g(k):
            m = re.search(rf'\n    {k}: (.+)', blk)
            return m.group(1).strip() if m else None
        players.append(dict(acc=acc, nick=g('nick'), team=g('team'), tname=g('team_name'),
                            pos=int(g('position')) if (g('position') or '').isdigit() else 0, reserve=g('reserve') == 'true'))
    out = {}
    for org, (tid, tn) in TEAMS.items():
        ps = [p for p in players if (tn is None and p['team'] == 'our') or (tn is not None and p['tname'] == tn)]
        out[org] = ps
        assert len([p for p in ps if not p['reserve']]) == 5, org
    return out


# ---------------------------------------------------------------- признаки
class Levels:
    """Уровень игрока: звание, лобби пабов, игра лучше/хуже лобби. eff — «реальный уровень» в MMR."""

    def __init__(self, ids, med_fallback):
        self.st = {a: pub_stats(a) for a in ids}
        self.fallback = med_fallback

    def medal_mmr(self, acc, rt=None):
        r = medal(acc, rt) or self.fallback
        return mmr(r)

    def eff(self, acc, c_imp, c_wr, prior=15, rt=None):
        md = self.medal_mmr(acc, rt)
        s = self.st.get(acc)
        if not s:
            return md
        base = s['lobby'] + c_imp * s['perf'] + c_wr * (s['wr'] - 0.5) * 1000
        return (s['n'] * base + prior * md) / (s['n'] + prior)


def side_value(side, val, core_w=1.0):
    ws, tot = 0.0, 0.0
    for acc, pos, rt in side:
        w = core_w if pos in (1, 2, 3) else 1.0
        tot += w * val(acc, rt)
        ws += w
    return tot / ws / 1000


# ---------------------------------------------------------------- логистическая модель с поправкой команд
def solve(A, b):
    n = len(A)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c]))
        M[c], M[p] = M[p], M[c]
        for r in range(n):
            if r != c and M[r][c]:
                k = M[r][c] / M[c][c]
                for j in range(c, n + 1):
                    M[r][j] -= k * M[c][j]
    return [M[i][n] / M[i][i] for i in range(n)]


def inverse(A):
    n = len(A)
    cols = [solve(A, [1.0 if i == j else 0.0 for i in range(n)]) for j in range(n)]
    return [list(x) for x in zip(*cols)]


def cholesky(C):
    n = len(C)
    L = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(i + 1):
            s = C[i][j] - sum(L[i][k] * L[j][k] for k in range(j))
            L[i][j] = math.sqrt(max(s, 1e-12)) if i == j else s / L[j][j]
    return L


class Logit:
    """logit P(A) = Σ coef·(признак A − признак B) + u_A − u_B; u ~ N(0, s²) (s=None — без поправки команд)."""

    def __init__(self, name, feats, s=None, desc=''):
        self.name, self.feats, self.s, self.desc = name, feats, s, desc

    def fit(self, maps):
        teams = sorted({m['a'] for m in maps} | {m['b'] for m in maps}) if self.s else []
        self.ix = {t: i for i, t in enumerate(teams)}
        F, T = len(self.feats), len(teams)
        n = F + T
        rows = []
        for m in maps:
            x = [f(m['sa']) - f(m['sb']) for f in self.feats] + [0.0] * T
            if T:
                x[F + self.ix[m['a']]] += 1
                x[F + self.ix[m['b']]] -= 1
            rows.append((x, m['win']))
        w = [0.0] * n
        for _ in range(40):
            g = [0.0] * n
            H = [[0.0] * n for _ in range(n)]
            for x, y in rows:
                z = sum(wi * xi for wi, xi in zip(w, x))
                p = 1 / (1 + math.exp(-max(-30, min(30, z))))
                nz = [i for i, xi in enumerate(x) if xi]
                for i in nz:
                    g[i] += (p - y) * x[i]
                    for j in nz:
                        H[i][j] += p * (1 - p) * x[i] * x[j]
            for i in range(F):
                g[i] += w[i] / 100
                H[i][i] += 1 / 100
            for i in range(F, n):
                g[i] += w[i] / self.s ** 2
                H[i][i] += 1 / self.s ** 2
            d = solve(H, g)
            w = [wi - di for wi, di in zip(w, d)]
            if max(abs(v) for v in d) < 1e-8:
                break
        self.w, self.H, self.F = w, H, F
        return self

    def rating(self, side, team_id, w=None):
        w = w or self.w
        r = sum(c * f(side) for c, f in zip(w, self.feats))
        if self.s and team_id in self.ix:
            r += w[self.F + self.ix[team_id]]
        return r

    def prob(self, m):
        return 1 / (1 + math.exp(-(self.rating(m['sa'], m['a']) - self.rating(m['sb'], m['b']))))

    def sampler(self):
        L = cholesky(inverse(self.H))
        n = len(self.w)

        def draw():
            z = [random.gauss(0, 1) for _ in range(n)]
            return [self.w[i] + sum(L[i][k] * z[k] for k in range(i + 1)) for i in range(n)]
        return draw


class Elo:
    """Сила из признака (коэффициент по прошлым картам) + поправка, которая обновляется после каждой карты на K·(итог − прогноз)."""

    def __init__(self, name, feat, k, desc=''):
        self.name, self.feat, self.k, self.desc, self.s = name, feat, k, desc, None

    def fit(self, maps):
        self.base = Logit('', [self.feat]).fit(maps)
        self.d = collections.defaultdict(float)
        for m in maps:
            p = self.prob(m)
            self.d[m['a']] += self.k * (m['win'] - p)
            self.d[m['b']] -= self.k * (m['win'] - p)
        return self

    def rating(self, side, team_id, w=None):
        return self.base.rating(side, None) + self.d[team_id]

    def prob(self, m):
        return 1 / (1 + math.exp(-(self.rating(m['sa'], m['a']) - self.rating(m['sb'], m['b']))))

    def sampler(self):
        return None


class Ensemble:
    def __init__(self, name, members, desc=''):
        self.name, self.members, self.desc, self.s = name, members, desc, None

    def fit(self, maps):
        self.fitted = [clone(m).fit(maps) for m in self.members]
        return self

    def prob(self, m):
        return sum(x.prob(m) for x in self.fitted) / len(self.fitted)


def clone(model):
    if isinstance(model, Logit):
        return Logit(model.name, model.feats, model.s, model.desc)
    if isinstance(model, Elo):
        return Elo(model.name, model.feat, model.k, model.desc)
    return Ensemble(model.name, model.members, model.desc)


def validate(model, maps):
    """Честно: карты каждого дня предсказываются моделью, обученной на картах строго раньше этого дня."""
    days = sorted({m['day'] for m in maps})
    ll = br = hit = n = 0
    for d in days[1:]:
        train = [m for m in maps if m['day'] < d]
        fitted = clone(model).fit(train)
        for m in (x for x in maps if x['day'] == d):
            p = min(max(fitted.prob(m), 1e-4), 1 - 1e-4)
            ll += -math.log(p if m['win'] else 1 - p)
            br += (p - m['win']) ** 2
            hit += (p > 0.5) == bool(m['win'])
            n += 1
    return dict(ll=ll / n, brier=br / n, acc=hit / n, n=n)


# ---------------------------------------------------------------- симуляция плей-офф
def lineup_side(ps):
    return [(p['acc'], p['pos'], None) for p in ps if not p['reserve']]


def simulate(model, lineups, n=SIMS):
    """Места и серии. Неопределённость логистической модели — выборки параметров (приближение Лапласа)."""
    prepared = ratings(model, lineups)
    place = collections.defaultdict(collections.Counter)
    series = collections.defaultdict(collections.Counter)
    meet = collections.defaultdict(collections.Counter)
    brackets, podium = collections.Counter(), collections.Counter()
    cond = {True: collections.Counter(), False: collections.Counter()}
    for i in range(n):
        rt = random.choice(random.choice(prepared))

        def play(a, b, key):
            meet[key][tuple(sorted((a, b)))] += 1
            if key in PLAYED:
                w = PLAYED[key]
                series[key][w] += 1
                return w
            p = bo3(1 / (1 + math.exp(-(rt[a] - rt[b]))))
            w = a if random.random() < p else b
            series[key][w] += 1
            return w
        pl, won = run_bracket(play)
        for t, k in pl.items():
            place[t][k] += 1
        inv = {v: k for k, v in pl.items()}
        podium[tuple(t for t, _ in sorted(pl.items(), key=lambda x: x[1])[:3])] += 1
        brackets[tuple(won[k][0] for k in SERIES)] += 1
        cond[won[NEXT[0]][0] == OURS][pl[OURS]] += 1
    return dict(place=place, series=series, meet=meet, brackets=brackets, podium=podium, cond=cond, n=n)


_RATINGS = {}


def ratings(model, lineups, k=1000):
    """Рейтинги восьми команд в k выборках параметров (у каждой модели ансамбля свои), один раз на модель:
    все пары считаются на одних и тех же выборках, поэтому шанс A против B и B против A в сумме дают 100%."""
    if id(model) not in _RATINGS:
        members = model.fitted if isinstance(model, Ensemble) else [model]
        sides = {o: lineup_side(ps) for o, ps in lineups.items()}
        out = []
        for mdl in members:
            draw = mdl.sampler()
            ws = [draw() for _ in range(k)] if draw else [None]
            out.append([{o: mdl.rating(sides[o], TEAMS[o][0], w) for o in TEAMS} for w in ws])
        _RATINGS[id(model)] = out
    return _RATINGS[id(model)]


def pair(model, a, b, lineups):
    """(шанс карты, шанс серии BO3) a против b, средние по неопределённости и по моделям ансамбля."""
    pm = ps = 0.0
    members = ratings(model, lineups)
    for samples in members:
        ps_m = [1 / (1 + math.exp(-(r[a] - r[b]))) for r in samples]
        pm += sum(ps_m) / len(ps_m)
        ps += sum(bo3(p) for p in ps_m) / len(ps_m)
    return pm / len(members), ps / len(members)


def mean_rating(model, a, b, lineups):
    return pair(model, a, b, lineups)[0]


# ---------------------------------------------------------------- отчёт
def pct(x, d=0):
    return f'{100 * x:.{d}f}%'


def rank_name(rt):
    if not rt:
        return '—'
    t, s = divmod(int(rt), 10)
    names = ['', 'Рекрут', 'Страж', 'Рыцарь', 'Герой', 'Легенда', 'Властелин', 'Божество', 'Титан']
    return names[t] + ('' if t >= 8 else f' {s}')


def mmr_name(v):
    best = min(((t, s) for t in range(1, 8) for s in range(1, 6)), key=lambda ts: abs(mmr(ts[0] * 10 + ts[1]) - v))
    if v > mmr(75) + 150:
        return 'Титан'
    return rank_name(best[0] * 10 + best[1])


def main():
    if not os.path.isdir(os.path.join(FC, 'pubs')):
        sys.exit('нет data/forecast/pubs — сначала python tools/forecast/fetch.py')
    maps = load_maps()
    lineups = load_lineups()
    ids = {a for m in maps for a, _, _ in m['sa'] + m['sb'] if a} | {p['acc'] for ps in lineups.values() for p in ps}
    rts = sorted(r for r in (medal(a) for a in ids) if r)
    lv = Levels(ids, rts[len(rts) // 2])

    def f_medal(side):
        return side_value(side, lambda a, rt: lv.medal_mmr(a, rt) if a else lv.medal_mmr(None, rt))

    def f_eff(ci, cw, core=1.0):
        return lambda side: side_value(side, lambda a, rt: lv.eff(a, ci, cw, rt=rt) if a else lv.medal_mmr(None, rt), core)

    def f_lobby(side):
        return side_value(side, lambda a, rt: lv.eff(a, 0, 0, rt=rt) if a else lv.medal_mmr(None, rt))

    print('карт', len(maps), 'игроков', len(ids), 'с пабами', sum(1 for a in ids if lv.st.get(a)), file=sys.stderr)
    # подбор «реального уровня»: сколько MMR даёт +1 IMP к лобби и +1 п. п. винрейта
    grid = []
    for ci in (0, 10, 20, 30):
        for cw in (0, 1, 2, 4):
            r = validate(Logit('', [f_eff(ci, cw)]), maps)
            grid.append((r['ll'], ci, cw, r))
            print('уровень', ci, cw, r, file=sys.stderr)
    grid.sort()
    _, CI, CW, _ = grid[0]
    eff = f_eff(CI, CW)
    core_grid = []
    for core in (0.5, 1.0, 1.5, 2.0):
        r = validate(Logit('', [f_eff(CI, CW, core)]), maps)
        core_grid.append((r['ll'], core, r))
    core_grid.sort()
    models = [
        Logit('Звание', [f_medal], desc='средний MMR званий пятёрки на карте'),
        Logit('Лобби пабов', [f_lobby], desc='средний MMR лобби, в которых игроки играют пабы (полгода, свежие важнее)'),
        Logit('Реальный уровень', [eff], desc=f'лобби пабов + {CI} MMR за каждый IMP сверх лобби + {CW * 10} MMR за каждый п. п. винрейта сверх 50%'),
        Logit('Звание + уровень', [f_medal, eff], desc='оба признака, веса подбираются по картам; признаки почти совпадают, и на первых днях веса разъезжаются в разные стороны'),
        Logit('Только результаты', [], s=0.75, desc='сила команды только по картам лиги, без рангов (Брэдли — Терри)'),
        Logit('Уровень + результаты', [eff], s=0.6, desc='реальный уровень и поправка команды по картам лиги с учётом силы соперников'),
        Logit('Звание + результаты', [f_medal], s=0.6, desc='звание и поправка команды по картам лиги (прошлая модель)'),
        Elo('Эло от уровня', eff, 0.25, desc='реальный уровень, поправка после каждой карты на 0.25·(итог − прогноз)'),
    ]
    vals = [(m, validate(m, maps)) for m in models]
    best3 = [m for m, _ in sorted(vals, key=lambda x: x[1]['ll'])[:3]]
    ens = Ensemble('Ансамбль', best3, desc='среднее трёх лучших моделей: ' + ', '.join(m.name for m in best3))
    vals.append((ens, validate(ens, maps)))
    vals.sort(key=lambda x: x[1]['ll'])
    final = ens
    print('модели', [(m.name, round(v['ll'], 3)) for m, v in vals], file=sys.stderr)
    fitted = {m.name: clone(m).fit(maps) for m, _ in vals}
    sims = {name: simulate(f, lineups) for name, f in fitted.items()}
    F = fitted[final.name]
    S = sims[final.name]
    write_report(maps, lineups, lv, CI, CW, grid, core_grid, vals, fitted, sims, final.name, f_medal, eff)


def write_report(maps, lineups, lv, CI, CW, grid, core_grid, vals, fitted, sims, final, f_medal, eff):
    F, S = fitted[final], sims[final]
    N = S['n']
    orgs = sorted(TEAMS, key=lambda o: (-S['place'][o][1], -(S['place'][o][1] + S['place'][o][2] + S['place'][o][3])))
    top3 = lambda o: (S['place'][o][1] + S['place'][o][2] + S['place'][o][3]) / N
    key, zv = NEXT
    pm = mean_rating(F, OURS, zv, lineups)
    L = []
    w = L.append
    w(f'Сгенерировано {datetime.date.today():%d.%m.%Y} скриптом `tools/forecast/forecast.py` по {len(maps)} картам лиги. '
      f'Главная модель — «{final}» (среднее трёх лучших моделей честной проверки, см. «Сравнение моделей»). Все серии считаются BO3, '
      f'сетка на двойное выбывание как на сайте турнира, {N} симуляций.')
    w('')
    played = ', '.join(f'{k} — {v}' for k, v in PLAYED.items())
    w(f'Уже сыграно (победители): {played}. Эти серии в симуляции не разыгрываются.')
    w('')
    w(f'**Следующий матч ({key}, нижняя сетка, проигравший выбывает): Долг — {zv}: карта {pct(pm)} на {pct(1 - pm)}, '
      f'серия BO3 {pct(S["series"][key][OURS] / N)} на {pct(S["series"][key][zv] / N)}.**')
    w('')
    w('| # | Команда | Победа в турнире | Топ-3 |')
    w('|---|---|---|---|')
    for i, o in enumerate(orgs, 1):
        b = '**' if o == OURS else ''
        w(f'| {i} | {b}{o}{b} | {pct(S["place"][o][1] / N, 1)} | {pct(top3(o))} |')
    w('')
    w('Подробности — в разделах ниже, они раскрываются по клику. Это прогноз ради интереса: модель видит ранги и '
      'результаты карт, но не драфт, форму в день игры и замены.')

    # --- места
    w('\n## Шансы на каждое место')
    w('| Команда | 1 | 2 | 3 | Топ-3 | 4 | 5–6 | 7–8 |')
    w('|---|---|---|---|---|---|---|---|')
    for o in orgs:
        c = S['place'][o]
        w(f'| {o} | {pct(c[1] / N, 1)} | {pct(c[2] / N, 1)} | {pct(c[3] / N, 1)} | **{pct(top3(o))}** | {pct(c[4] / N)} | {pct(c[5] / N)} | {pct(c[7] / N)} |')
    w('\n5–6 — вылет в L3/L4, 7–8 — в L1/L2.')

    # --- сила команд
    w('\n## Сила команд и составы')
    w('Рейтинг — сила в логитах главной модели: разница 0.4 ≈ 60 на 40 на карте. Уровень — «реальный уровень» пятёрки '
      '(см. «Реальный уровень игроков»).')
    w('')
    w('| Команда | Звание (среднее) | Реальный уровень (среднее) | Карт в лиге | Состав (звание → уровень) |')
    w('|---|---|---|---|---|')
    for o in sorted(TEAMS, key=lambda o: -eff(lineup_side(lineups[o]))):
        side = lineup_side(lineups[o])
        tid = TEAMS[o][0]
        nmaps = sum(1 for m in maps if tid in (m['a'], m['b']))
        ps = ', '.join(f'{p["nick"]} ({p["pos"]}): {rank_name(medal(p["acc"]))} → {mmr_name(lv.eff(p["acc"], CI, CW))}'
                       for p in sorted(lineups[o], key=lambda p: p['pos']) if not p['reserve'])
        w(f'| {o} | {f_medal(side) * 1000:.0f} ({mmr_name(f_medal(side) * 1000)}) | {eff(side) * 1000:.0f} ({mmr_name(eff(side) * 1000)}) | {nmaps} | {ps} |')
    res = fitted.get('Уровень + результаты')
    if res:
        w('\nПоправка по результатам («Уровень + результаты»): насколько команда выступала в лиге лучше (+) или хуже (−) '
          'своего уровня с учётом силы соперников, в логитах.')
        w('')
        w('| Команда | Поправка |')
        w('|---|---|')
        for o in sorted(TEAMS, key=lambda o: -res.w[res.F + res.ix[TEAMS[o][0]]] if TEAMS[o][0] in res.ix else 0):
            tid = TEAMS[o][0]
            w(f'| {o} | {res.w[res.F + res.ix[tid]]:+.2f} |' if tid in res.ix else f'| {o} | нет карт |')

    # --- пары
    w('\n## Все пары: шанс выиграть серию BO3')
    w('Строка против столбца. В скобках — шанс выиграть одну карту.')
    w('')
    w('| | ' + ' | '.join(orgs) + ' |')
    w('|---' * (len(orgs) + 1) + '|')
    for a in orgs:
        cells = []
        for b in orgs:
            if a == b:
                cells.append('—')
            else:
                p, sp = pair(F, a, b, lineups)
                cells.append(f'{pct(sp)} ({pct(p)})')
        w(f'| **{a}** | ' + ' | '.join(cells) + ' |')

    # --- наш путь
    w('\n## Наш путь')
    cw, cl = S['cond'][True], S['cond'][False]
    nw, nl = sum(cw.values()), sum(cl.values())
    w(f'Выигрываем у {NEXT[1]} в {pct(nw / N)} симуляций, проигрываем в {pct(nl / N)}.')
    w('')
    w(f'| Наше место | Если выиграли {NEXT[0]} | Если проиграли {NEXT[0]} | Всего |')
    w('|---|---|---|---|')
    for k, name in ((1, '1'), (2, '2'), (3, '3'), (4, '4'), (5, '5–6'), (7, '7–8')):
        w(f'| {name} | {pct(cw[k] / max(1, nw))} | {pct(cl[k] / max(1, nl))} | {pct(S["place"][OURS][k] / N)} |')
    w('')
    w('Кого мы встречаем и с каким шансом выиграть серию:')
    w('')
    w('| Соперник | Серия BO3 | Карта |')
    w('|---|---|---|')
    for b in sorted((o for o in TEAMS if o != OURS), key=lambda b: -mean_rating(F, OURS, b, lineups)):
        p, sp = pair(F, OURS, b, lineups)
        w(f'| {b} | {pct(sp)} | {pct(p)} |')

    # --- самый вероятный ход
    w('\n## Самый вероятный ход сетки')
    w('В каждой серии — более вероятный победитель по главной модели.')
    w('')
    w('| Серия | Пара | Победитель | Шанс |')
    w('|---|---|---|---|')

    def greedy(a, b, key):
        p = pair(F, a, b, lineups)[1]
        win = a if p >= 0.5 else b
        w(f'| {SERIES_NAME[key]} | {a} — {b} | {win} | {pct(max(p, 1 - p))} |')
        return win
    place, _ = run_bracket(greedy)
    pod = sorted(place.items(), key=lambda x: x[1])
    w('')
    w(f'Итог: 1 — {pod[0][0]}, 2 — {pod[1][0]}, 3 — {pod[2][0]}, 4 — {pod[3][0]}.')
    w('')
    w('Ровно такая сетка целиком сбывается редко: в каждой серии есть шанс неожиданности. Ниже — частота подиумов и целых сеток в симуляциях.')

    # --- подиумы и сетки
    w('\n## Частые подиумы и варианты сетки')
    w('| Вероятность | 1 | 2 | 3 |')
    w('|---|---|---|---|')
    for (a, b, c), k in S['podium'].most_common(10):
        w(f'| {pct(k / N, 1)} | {a} | {b} | {c} |')
    w('')
    w('Пять самых частых сеток целиком (победитель каждой серии):')
    w('')
    w('| Вероятность | ' + ' | '.join(SERIES) + ' |')
    w('|---' * (len(SERIES) + 1) + '|')
    for br, k in S['brackets'].most_common(5):
        w(f'| {pct(k / N, 2)} | ' + ' | '.join(t.replace('НПП ', '') for t in br) + ' |')

    # --- каждая серия
    w('\n## Кто играет и выигрывает каждую серию')
    w('| Серия | Самые частые пары | Чаще выигрывает |')
    w('|---|---|---|')
    for key in SERIES:
        pairs = ', '.join(f'{a} — {b} {pct(k / N)}' for (a, b), k in S['meet'][key].most_common(3))
        wins = ', '.join(f'{t} {pct(k / N)}' for t, k in S['series'][key].most_common(3))
        w(f'| {SERIES_NAME[key]} | {pairs} | {wins} |')

    # --- модели
    w('\n## Сравнение моделей')
    w('Честная проверка: карты каждого игрового дня лиги предсказываются моделью, обученной только на картах до этого '
      'дня. Log-loss — чем меньше, тем лучше (монетка — 0.693); Брайер — средний квадрат ошибки (монетка — 0.25).')
    w('')
    w('| Модель | Что учитывает | Log-loss | Брайер | Угадано карт |')
    w('|---|---|---|---|---|')
    for m, v in vals:
        b = '**' if m.name == final else ''
        w(f'| {b}{m.name}{b} | {m.desc} | {v["ll"]:.3f} | {v["brier"]:.3f} | {pct(v["acc"])} из {v["n"]} |')
    w('')
    w('Прогноз по каждой модели:')
    w('')
    names = [m.name for m, _ in vals]
    w('| Команда | ' + ' | '.join(names) + ' |')
    w('|---' * (len(names) + 1) + '|')
    for o in orgs:
        w(f'| {o} | ' + ' | '.join(f'{pct(sims[n]["place"][o][1] / N)} / {pct(sum(sims[n]["place"][o][k] for k in (1, 2, 3)) / N)}' for n in names) + ' |')
    w('')
    w('В ячейке: победа в турнире / топ-3.')
    w('')
    w(f'| Модель | Долг — {zv}, серия |')
    w('|---|---|')
    for n in names:
        w(f'| {n} | {pct(sims[n]["series"][key][OURS] / N)} на {pct(sims[n]["series"][key][zv] / N)} |')
    w('')
    w('Подбор «реального уровня» (log-loss признака уровня в одиночку):')
    w('')
    w('| MMR за 1 IMP сверх лобби | MMR за 1 п. п. винрейта | Log-loss |')
    w('|---|---|---|')
    for ll, ci, cw2, _ in sorted(grid, key=lambda g: (g[1], g[2])):
        b = '**' if (ci, cw2) == (CI, CW) else ''
        w(f'| {b}{ci}{b} | {b}{cw2 * 10}{b} | {ll:.3f} |')
    w('')
    w('Вес коров (позиции 1–3) против саппортов в среднем уровне пятёрки: ' +
      ', '.join(f'×{c}: {ll:.3f}' for ll, c, _ in sorted(core_grid, key=lambda g: g[1])) +
      '. Модели выше считают всех пятерых поровну.')

    # --- игроки
    w('\n## Реальный уровень игроков')
    w('Уровень = средний MMR лобби в пабах за полгода (свежие игры важнее, полураспад 60 дней) + поправка за игру '
      f'лучше или хуже лобби: {CI} MMR за каждый пункт IMP сверх среднего IMP остальных девяти, {CW * 10} MMR за '
      'каждый процентный пункт винрейта сверх 50%. При малом числе игр уровень тянется к званию. Турбо не считается.')
    w('')
    w('| Команда | Игрок | Поз. | Звание | Лобби пабов | IMP сверх лобби | Винрейт | Игр | Уровень | Уровень − звание |')
    w('|---|---|---|---|---|---|---|---|---|---|')
    for o in orgs:
        for p in sorted(lineups[o], key=lambda p: (p['reserve'], p['pos'])):
            s = lv.st.get(p['acc'])
            md = lv.medal_mmr(p['acc'])
            e = lv.eff(p['acc'], CI, CW)
            nick = p['nick'] + (' (запас)' if p['reserve'] else '')
            if s:
                w(f'| {o} | {nick} | {p["pos"] or "—"} | {rank_name(medal(p["acc"]))} | {mmr_name(s["lobby"])} | {s["perf"]:+.1f} | '
                  f'{pct(s["wr"])} | {s["n"]} | {mmr_name(e)} ({e:.0f}) | {e - md:+.0f} |')
            else:
                w(f'| {o} | {nick} | {p["pos"] or "—"} | {rank_name(medal(p["acc"]))} | нет пабов | — | — | 0 | = званию | 0 |')

    # --- методика
    w('\n## Как считалось и ограничения')
    w('- Сила карты: P(победа) = 1 / (1 + e^−(рейтинг A − рейтинг B)), рейтинг — линейная функция среднего MMR пятёрки '
      '(званий или реального уровня) плюс, в моделях «+ результаты», поправка команды по картам лиги. Поправка '
      'стянута к нулю: при 5–10 картах она не может перебить уровень, а сила соперников учтена, поэтому победы в лёгкой группе стоят меньше.')
    w('- Ранги — текущие (STRATZ), а не на момент карты; пабы взяты за последние полгода, в том числе после карт лиги. '
      'Для честной проверки это небольшая утечка: уровень игрока за месяц меняется слабо.')
    w('- Состав на плей-офф — из `config/players.yaml` без запасных. Для соперника с заменой прогноз может быть хуже.')
    w('- Серии считаются как независимые карты; формат BO3 у всех серий, у победителя верхней сетки в гранд-финале '
      'нет преимущества. Если формат другой, шансы фаворитов в BO1 ниже, в BO5 выше.')
    w('- Неопределённость моделей учтена: в каждой симуляции берутся свои параметры из приближения Лапласа.')
    w('- Модель не видит драфт, пулы героев, форму в день игры, связь между игроками.')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(L) + '\n')
    print('записано', OUT, file=sys.stderr)


if __name__ == '__main__':
    main()
