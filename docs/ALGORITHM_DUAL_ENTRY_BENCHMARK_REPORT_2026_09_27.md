# Полный отчёт: алгоритмы после правила свободного финального входа в ОКС

Дата: 2026-09-27

> **Статус: эксперимент, не официальный конкурсный benchmark.** Этот отчёт использует
> `EXPERIMENTAL_ANY_BOUNDARY_V1`. После сверки с п. 2.2 технического приложения
> production API и независимый checker переведены на `DOCUMENT_NEAREST_V1`, где
> конечный участок обязан проходить через ближайшую границу ОКС. Актуальный вывод:
> `docs/OFFICIAL_API_ALGORITHM_REPORT_2026_09_27.md`.

## Итог

Production-алгоритм: **PORTFOLIO**. На контрольном Иннополисе он выбирает X2 и даёт `6/6`, `434.87 м`, `58.97 млн руб.`, `score=2.96`, `0` нарушений. На больших или тяжёлых сценах главным надёжным incumbent остаётся B3/R2, но полнота подключения ограничивается генератором допустимых маршрутов.

Важно: одинаковый лучший score до и после объединения стратегий не означает, что свободный вход не дал улучшения. Честная абляция одного и того же B3 на Иннополисе показала: обязательный вход через портал даёт `723.79 м`, `97.18 млн руб.`, `score=4.89`, а прямой финальный вход — `442.47 м`, `66.97 млн руб.`, `score=3.20`. Экономия составляет `281.32 м` (`38.87%`) и `30.20 млн руб.` (`31.08%`). Итоговый PORTFOLIO затем нашёл ещё более дешёвую топологию X2 в режиме `PORTAL_ONLY`, поэтому production-результат `2.96` остался лучшим из обоих миров.

Главный отрицательный вывод: текущий **R1 нельзя использовать самостоятельно**. После изменения endpoint/action space он систематически теряет покрытие и требует переобучения. R2 надёжнее, потому что сохраняет B3 incumbent.

## Реализованные изменения

1. Для каждого алгоритма добавлены два режима: `DIRECT_ALLOWED` и `PORTAL_ONLY`.
2. Финальный участок может пересечь собственное здание с любой стороны; транзит через ОКС запрещён.
3. ОКС больше не используется как родитель/транзитный узел: общие ветви соединяются через exterior junction.
4. B2/B3/R2/X0 переведены на junction-safe построение дерева.
5. Общий бюджет делится между двумя режимами, поэтому один режим не может вытеснить другой.
6. B3 сначала сохраняет сильный B2-U incumbent и только затем запускает дорогой repair.
7. Route cache поднят до `route-cache-v4-dual-entry-strategy`.

## Методика

- Основной сравнимый срез: `7` полностью завершённых test-сцен, `99` применимых запусков.
- На сценах свыше 6 ОКС X0/X1/X2 ожидаемо имеют `NOT_APPLICABLE` и не считаются ошибкой.
- Ранжирование: сначала число подключённых ОКС, затем score при равном покрытии.
- Каждый сохранённый результат независимо проверен `benchmark_checker.py`.
- Восьмая tie-in stress сцена была перезапущена отдельно после найденного и исправленного дефекта budget monopolization; её результаты приведены отдельно и не смешаны со средними.

## Честная A/B-проверка правила входа

Для измерения именно этой правки в API и benchmark runner добавлен принудительный режим `entryStrategy`: `PORTAL_ONLY`, `DIRECT_ALLOWED` или production-режим `AUTO`. В A/B не меняются алгоритм, входные данные, checker и бюджет.

### Иннополис, одинаковый B3

| Режим | ОКС | Длина, м | Стоимость, руб. | Score | Нарушения |
|---|---:|---:|---:|---:|---:|
| `PORTAL_ONLY` | 6/6 | 723.79 | 97 177 721.00 | 4.89 | 0 |
| `DIRECT_ALLOWED` | 6/6 | 442.47 | 66 973 130.54 | 3.20 | 0 |

Результат: `−281.32 м` (`−38.87%`), `−30 204 590.46 руб.` (`−31.08%`), score улучшен на `1.69` (`−34.56%`). Полный машинно-проверенный отчёт: `results/entry-strategy-ablation-b3-innopolis-2026-09-27/summary.md`.

### Сцена shared-pipe из общего среза

На `026_spb_parnas_+1_+2_a036bc71` порталный B3 подключил только `4/6` ОКС за `273.70 млн руб.`, а прямой вход подключил `6/6` за `105.47 млн руб.`. Здесь улучшение правила выражается прежде всего в полноте: плюс два ОКС и одновременно минус `168.23 млн руб.`. Остальные результаты среза сохранены в `results/entry-strategy-ablation-b3-2026-09-27/summary.md`.

## Агрегат по завершённым test-сценам

| Алгоритм | VALID/применимо | Полное подключение | Победы | Среднее покрытие | Mean score | Mean solver, с | Выбранный вход |
|---|---:|---:|---:|---:|---:|---:|---|
| B3 | 7/7 | 2/7 | 7 | 71.0% | 12.759 | 52.98 | DIRECT_ALLOWED:7 |
| R1-PILOT | 7/7 | 2/7 | 7 | 71.0% | 12.759 | 52.65 | DIRECT_ALLOWED:7 |
| R2 | 7/7 | 2/7 | 7 | 71.0% | 12.759 | 59.23 | DIRECT_ALLOWED:7 |
| PORTFOLIO | 7/7 | 2/7 | 7 | 71.0% | 12.759 | 272.15 | DIRECT_ALLOWED:7 |
| X0 | 5/5 | 2/5 | 5 | 82.7% | 8.740 | 50.50 | DIRECT_ALLOWED:5 |
| X1 | 5/5 | 2/5 | 5 | 82.7% | 8.740 | 242.98 | DIRECT_ALLOWED:5 |
| X2 | 5/5 | 2/5 | 5 | 82.7% | 8.740 | 261.06 | DIRECT_ALLOWED:5 |
| B2-U | 7/7 | 1/7 | 3 | 68.6% | 13.757 | 46.17 | DIRECT_ALLOWED:7 |
| B2-C | 7/7 | 1/7 | 3 | 68.6% | 13.813 | 44.64 | DIRECT_ALLOWED:7 |
| B2-C-J | 7/7 | 1/7 | 3 | 68.6% | 13.813 | 44.57 | DIRECT_ALLOWED:7 |
| B2-Q | 7/7 | 1/7 | 2 | 68.6% | 13.773 | 42.97 | DIRECT_ALLOWED:7 |
| B0-GRID | 7/7 | 1/7 | 2 | 66.2% | 14.894 | 38.50 | DIRECT_ALLOWED:7 |
| B0-CORRIDOR | 7/7 | 1/7 | 2 | 66.2% | 14.896 | 39.04 | DIRECT_ALLOWED:7 |
| B1 | 7/7 | 1/7 | 2 | 66.2% | 14.980 | 52.20 | DIRECT_ALLOWED:7 |
| R1 | 7/7 | 1/7 | 1 | 39.0% | 16.386 | 236.94 | DIRECT_ALLOWED:7 |

## Победители по сценам

| Сцена | Победитель | Покрытие | Score | Время solver, с |
|---|---|---:|---:|---:|
| `moscow_center_+1_+0_576e8067_large_oks` | B0-CORRIDOR | 4/4 | 5.48 | 1.84 |
| `moscow_center_-3_+2_a38ab22e` | B0-CORRIDOR | 2/3 | 6.93 | 146.78 |
| `moscow_center_-3_+3_f652c69f_many_connection_points` | B3 | 4/12 | 27.06 | 115.73 |
| `moscow_center_-3_-3_fab797d4` | B2-C | 4/5 | 13.51 | 49.47 |
| `spb_parnas_+1_+1_6bad179c_mixed_scale` | B3 | 4/8 | 18.55 | 11.77 |
| `spb_parnas_+1_+2_a036bc71` | B3 | 6/6 | 4.94 | 2.50 |
| `spb_parnas_-3_+1_1622f8c6` | B3 | 4/6 | 12.84 | 51.61 |

## Недостатки конкретных алгоритмов

| Семейство | Что умеет | Конкретный недостаток | Следующее улучшение |
|---|---|---|---|
| B0-GRID / B0-CORRIDOR | Надёжный независимый baseline | Не строит общий ствол; на tie-in stress было 0/8 | Использовать только как fallback и lower-level route probe |
| B1 | Regret-порядок без незаконного транзита через ОКС | После запрета consumer-as-parent почти не разделяет сеть | Добавить regret по exterior junction-кандидатам |
| B2-U/Q/C | Быстро создаёт общий backbone | Разные веса часто дают одинаковую топологию и тратят бюджет повторно | Дедупликация topology hash и адаптивный выбор medoid seed |
| B2-C-J | Законные junction внутри существующей новой трубы | Не помог на нескольких geometry-limited сценах | Расширить позиции junction и разрешить совместный repair 2-4 ОКС |
| B3 | Самый устойчивый standalone heuristic | Повторно оценивает эквивалентные seeds; без новых коридоров не повышает покрытие | Candidate dedup, negative-path cache, региональный topology repair |
| R1-PILOT | Обычно сохраняет B3 seed | Почти не даёт улучшений поверх incumbent | Не считать production-RL; использовать как ablation |
| R1 | Иногда улучшает малую сцену | Систематически теряет покрытие на stress-сценах после изменения action space | Переобучить на v4 candidates; всегда держать certified incumbent |
| R2 | Сохраняет B3 и безопасно пробует repair | В измерениях редко улучшает B3 | Обучить оператор на реальных выигрышах region repair |
| X0/X1 | Контроль перебора порядка/родительских деревьев | Точен только относительно текущего route shortlist, не непрерывной геометрии | Использовать как oracle для малых сцен, не как production |
| X2 | Лучший результат на Иннополисе | Ограничен 6 ОКС и дорог; не помогает, если нужного ребра нет в графе | Больше boundary/visibility candidates и декомпозиция регионов |
| PORTFOLIO | Лучший безопасный выбор между семействами | Наследует runtime всех веток и потолок route generator | Адаптивный dispatcher, ранняя остановка дубликатов, новый geometry layer |

## Главный технический потолок

На нескольких сценах B2, B3, R2, X0, X1 и X2 дают одинаковое покрытие. Это означает, что дальнейший перебор дерева не поможет: допустимого ребра нет в candidate graph. Следующий приоритет для качества: boundary-aware visibility graph, расширенный long-range A*, несколько порталов на каждую сторону здания, negative-path cache и локальная совместная перестройка 2-4 ОКС.

## Контроль Иннополиса: все 15 алгоритмов

# Algorithm benchmark

- input: `data\rl_large\scenes\shared_pipe\076_innopolis_-1_-3_f0abd3bb.geojson`
- generated_at: `2026-09-27T18:17:29+0300`
- run configuration: `results\all-algorithms-dual-entry-smoke-v4-2026-09-27\run-manifest.json`
- configuration id: `aea44d4160e9541ac902a3e7e85f40b3a0fb3eb69adbc9e1c8f8277c261a972f`
- best_full_connection_algorithm: `PORTFOLIO`
- best_coverage_algorithm: `PORTFOLIO`

| rank | algorithm | status | oks | length_m | cost_rub | score | time_s | violations |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| - | B0-GRID | VALID | 4/6 | 586.37 | 292101486.5 | 9.94 | 0.435 | 0 |
| - | B0-CORRIDOR | VALID | 4/6 | 589.9 | 292379603.59 | 9.96 | 0.441 | 0 |
| - | B1 | VALID | 4/6 | 577.88 | 276362375.63 | 9.47 | 0.411 | 0 |
| 12 | B2-U | VALID | 6/6 | 555.08 | 78042545.95 | 3.85 | 0.523 | 0 |
| 11 | B2-Q | VALID | 6/6 | 555.08 | 78042545.95 | 3.85 | 0.576 | 0 |
| 9 | B2-C | VALID | 6/6 | 555.08 | 78042545.95 | 3.85 | 0.515 | 0 |
| 10 | B2-C-J | VALID | 6/6 | 555.08 | 78042545.95 | 3.85 | 0.57 | 0 |
| 3 | B3 | VALID | 6/6 | 442.47 | 66973130.54 | 3.2 | 2.137 | 0 |
| 5 | R1-PILOT | VALID | 6/6 | 442.47 | 66973130.54 | 3.2 | 1.645 | 0 |
| 4 | R1 | VALID | 6/6 | 442.47 | 66973130.54 | 3.2 | 12.553 | 0 |
| 6 | R2 | VALID | 6/6 | 442.47 | 66973130.54 | 3.2 | 1.419 | 0 |
| 7 | X0 | VALID | 6/6 | 442.47 | 66973130.54 | 3.2 | 0.593 | 0 |
| 8 | X1 | VALID | 6/6 | 442.47 | 66973130.54 | 3.2 | 49.213 | 0 |
| 2 | X2 | VALID | 6/6 | 434.87 | 58966039.64 | 2.96 | 50.794 | 0 |
| 1 | PORTFOLIO | VALID | 6/6 | 434.87 | 58966039.64 | 2.96 | 51.682 | 0 |

## Interpretation

- Ranking includes only checker-valid outputs that connect every input OKS.
- `R1` is the trained experimental PPO policy over concrete parameterized actions.
- `R1-PILOT` is the preserved compatibility control over whole-solver proposals.
- `R2` is the trained experimental destroy/repair operator controller.
- `NOT_APPLICABLE` is expected for X0 when the input has more than 6 connection points.
- `IMPLEMENTED_LITE` algorithms are executable research baselines, not paper-equivalent claims.


## Tie-in stress после исправления бюджета

# Algorithm benchmark

- input: `data\rl_large\scenes\tie_in_stress\036_moscow_center_+3_+0_40c3dc63.geojson`
- generated_at: `2026-09-27T18:11:42+0300`
- run configuration: `results\tie-in-stress-dual-budget-fix-2026-09-27\run-manifest.json`
- configuration id: `579a6882b091f10805bea57442c7f2073f48180718e99fbff0cbada9971f2b89`
- best_full_connection_algorithm: `-`
- best_coverage_algorithm: `B2-U`

| rank | algorithm | status | oks | length_m | cost_rub | score | time_s | violations |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| - | B2-U | VALID | 4/8 | 309.31 | 483513422.09 | 14.47 | 115.407 | 0 |
| - | B3 | VALID | 4/8 | 309.31 | 483513422.09 | 14.47 | 115.136 | 0 |
| - | R1 | VALID | 0/8 | 0 | 836344500.0 | 23.42 | 115.121 | 0 |
| - | R2 | VALID | 4/8 | 309.31 | 483513422.09 | 14.47 | 115.101 | 0 |
| - | PORTFOLIO | VALID | 4/8 | 309.31 | 483513422.09 | 14.47 | 115.109 | 0 |

## Interpretation

- Ranking includes only checker-valid outputs that connect every input OKS.
- `R1` is the trained experimental PPO policy over concrete parameterized actions.
- `R1-PILOT` is the preserved compatibility control over whole-solver proposals.
- `R2` is the trained experimental destroy/repair operator controller.
- `NOT_APPLICABLE` is expected for X0 when the input has more than 6 connection points.
- `IMPLEMENTED_LITE` algorithms are executable research baselines, not paper-equivalent claims.


## Ограничения выводов

- Это инженерный benchmark текущего deterministic test-среза, не доказательство глобальной оптимальности.
- Средний score между сценами с разным числом ОКС вторичен; покрытие всегда важнее.
- R1 обучался до изменения пространства допустимых endpoint/junction действий, поэтому его текущий результат честно считается устаревшим.
- Для окончательной статистики после следующего улучшения route generator нужен повтор 112-scene benchmark.
