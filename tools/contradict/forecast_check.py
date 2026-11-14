"""Тест модели прогноза плей-офф на противоречивых данных (docs/research/contradictory-data.md).

Подмешивает к настоящим картам лиги карты из testdata/contradictory/fake_team_visual.json разными дозами, без проверки
данных и с ней (forecast.map_problem), и смотрит на честный log-loss по настоящим картам, шанс нашей карты против
«Звезды» и поправку нашей команды. Запуск: python tools/contradict/forecast_check.py
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'forecast'))
import forecast as F  # noqa: E402

FAKE = os.path.join(ROOT, 'testdata', 'contradictory', 'fake_team_visual.json')
OUR_ID = 10255027


def validate_real(model, maps, real_keys):
    """Как F.validate, но оцениваются только настоящие карты (по id, итогу и времени: поддельный дубль с тем же id
    не считается), обучение — на всех картах раньше дня."""
    key = lambda m: (m['id'], m['win'], m['t'])
    days = sorted({m['day'] for m in maps if key(m) in real_keys})
    ll = hit = n = 0
    for d in days[1:]:
        fitted = F.clone(model).fit([m for m in maps if m['day'] < d])
        for m in (x for x in maps if x['day'] == d and key(x) in real_keys):
            p = min(max(fitted.prob(m), 1e-4), 1 - 1e-4)
            ll += -math.log(p if m['win'] else 1 - p)
            hit += (p > 0.5) == bool(m['win'])
            n += 1
    return ll / n, hit / n, n


def main():
    fake = json.load(open(FAKE, encoding='utf-8'))
    by_case = {}
    for m in fake:
        by_case.setdefault(m['_case'], []).append(m)
    poison, plausible = by_case.pop('poison'), by_case.pop('poison_plausible')
    broken = [m for ms in by_case.values() for m in ms]

    real = F.load_maps()
    real_keys = {(m['id'], m['win'], m['t']) for m in real}
    lineups = F.load_lineups()
    ids = {a for m in real for a, _, _ in m['sa'] + m['sb'] if a} | {p['acc'] for ps in lineups.values() for p in ps}
    rts = sorted(r for r in (F.medal(a) for a in ids) if r)
    lv = F.Levels(ids, rts[len(rts) // 2])

    def eff(side):  # «реальный уровень» с весами из прогноза: 0 MMR за IMP, 20 MMR за п. п. винрейта
        return F.side_value(side, lambda a, rt: lv.eff(a, 0, 2, rt=rt) if a else lv.medal_mmr(None, rt))
    model = F.Logit('Уровень + результаты', [eff], s=0.6)

    scenarios = [('только настоящие', [])] + [(f'+ {k} с чужими героями', poison[:k]) for k in (10, 100, 1000)] + [
        ('+ 1000 с чужими героями + битые', poison + broken), ('+ 300 правдоподобных подделок', plausible)]
    print('| Данные | Карт в модели | log-loss на настоящих | угадано | Долг — «Звезда», карта | поправка Долга |')
    print('|---|---|---|---|---|---|')
    real_problem = F.map_problem
    for name, extra in scenarios:
        for guard in ([False] if not extra else [False, True]):
            report = {}
            if guard:
                F.map_problem = real_problem
            else:  # без проверки: только дубли match_id, как раньше (иначе словарь карт ломается)
                F.map_problem = lambda m, seen: None
            maps = F.load_maps(extra, report)
            F.map_problem = real_problem
            ll, acc, n = validate_real(model, maps, real_keys)
            fitted = F.clone(model).fit(maps)
            zv = [(a, p, None) for a, p, _ in F.lineup_side(lineups['НПП «Звезда»'])]
            us = F.lineup_side(lineups[F.OURS])
            p = 1 / (1 + math.exp(-(fitted.rating(us, OUR_ID) - fitted.rating(zv, F.TEAMS['НПП «Звезда»'][0]))))
            u = fitted.w[fitted.F + fitted.ix[OUR_ID]]
            label = name + (', с проверкой' if guard else '')
            note = '' if not report else ' (' + ', '.join(f'{k} — {v}' for k, v in sorted(report.items())) + ')'
            print(f'| {label}{note} | {len(maps)} | {ll:.3f} | {acc:.0%} из {n} | {p:.0%} | {u:+.2f} |')


if __name__ == '__main__':
    main()
