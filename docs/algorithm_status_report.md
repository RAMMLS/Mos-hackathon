# Отчет по статусу алгоритмов и валидации

Дата: 2026-09-19

> Обновление после реализации матрицы алгоритмов: актуальный статус и результаты находятся в
> `docs/FULL_ALGORITHM_AND_RL_REPORT_2026_09_19.md`. Разделы ниже сохранены как исторический отчет о состоянии
> baseline до этой реализации и больше не являются текущим перечнем возможностей.

## Короткий ответ

Сейчас в коде работает не один из “больших” алгоритмов целиком, а практический MVP-гибрид:

**production default: B0/B1-lite greedy shared tree**

То есть:

- от B0 взят базовый принцип пошагового подключения ОКС к существующей сети;
- от B1 взята идея присоединения к уже построенной новой ветке, пересчета общих расходов и ограничения степени камеры;
- от B2/B3/R1 пока взяты только требования к бенчмаркам и направлению развития, но сами алгоритмы полностью не реализованы;
- RL-алгоритмы R1/R2 сейчас не реализованы.

В терминах документа `NETWORK_OPTIMIZATION_IMPLEMENTATION.md` текущий solver ближе всего к:

```text
B0 + часть B1
```

Это еще не полный B1, не B2, не B3 и не R1.

## Что описано в пакете GPT-6 Pro

В пакете описана линейка алгоритмов:

| Код | Смысл | Статус в проекте |
|---|---|---|
| B0 | Независимые подключения ОКС | Частично реализовано как базовый baseline |
| B1 | Вставки ОКС в новую сеть, общие ветки, экономия объединения | Частично реализовано |
| B2 | Взвешенные центры и общая магистраль | Не реализовано |
| B3 | ALNS-поиск без нейросети | Не реализовано |
| R1 | RL/PPO, выбирает конкретные проектные макродействия | Не реализовано |
| R2 | RL-контроллер операторов ALNS | Не реализовано |
| X0 | Точный перебор маленького кандидатного графа | Не реализовано |

## Что реально реализовано сейчас

### 1. Baseline tree solver

Файл:

```text
src/main/java/ru/moshackathon/heatnetwork/solver/BaselineSolver.java
```

Текущий режим:

```text
tree mode: greedy shared new-network branches, no existing-network reconstruction
```

Что делает:

1. Сортирует точки подключения ОКС по расходу.
2. Для каждой точки ищет кандидатов врезки в существующую сеть/камеру.
3. Разрешает подключение не только к старой сети, но и к уже построенной новой ветке.
4. Строит дерево новых труб.
5. Пересчитывает расход на общей ветке как сумму downstream ОКС.
6. Подбирает диаметр по каталогу.
7. Экспортирует:
   - `heat_network`;
   - `tie_in`;
   - `heat_chamber`;
   - `variant_summary`.

Что добавлено из B1:

- новые ОКС могут подключаться к уже построенным ОКС/веткам;
- общая ветка получает суммарный расход;
- branch chamber создается в точках ветвления;
- степень камеры ограничена до 4;
- добавлена экспериментальная B1-lite regret insertion логика, но она не включена как default, потому что первый прогон ухудшил benchmark.

Почему B1-lite не включен как основной:

```text
Он технически реализован как экспериментальная логика,
но прямое включение ухудшило результат benchmark.
Правильный следующий шаг: archive кандидатов, где greedy/B1/B2 строят варианты,
а checker выбирает лучший валидный.
```

### 2. RoutePlanner

Файл:

```text
src/main/java/ru/moshackathon/heatnetwork/solver/RoutePlanner.java
```

Что делает:

1. Пробует прямой маршрут.
2. Если есть запретное пересечение, пробует dogleg.
3. Если dogleg не подходит, запускает A* по сетке.
4. Учитывает специальные коэффициенты:
   - road;
   - tram_tracks / railway;
   - gas_pipeline;
   - power_cable;
   - heat_network.

Что усилено:

- запрещены маршруты через forbidden restrictions:
  - `oks`;
  - `water`;
  - `park`;
  - `social_area`;
  - `prohibited_site`;
- запрещены плохие пересечения дорог/трамвайных путей под углом меньше 45 градусов;
- добавлено сглаживание hairpin-разворотов;
- финальный A* маршрут перепроверяется целиком, а не только отдельные сеточные ребра.

### 3. MetricProjector

Файл:

```text
src/main/java/ru/moshackathon/heatnetwork/geo/MetricProjector.java
```

Что исправлено:

- обратная проекция `metric -> lon/lat` теперь уточняется итерационно;
- это убрало системную ошибку endpoint mismatch около 1.5 м на кейсе Иннополиса.

## Что реализовано для надежной валидации

### 1. Независимый checker

Файл:

```text
scripts/benchmark_checker.py
```

Checker не строит маршруты заново. Он проверяет готовый `result.geojson` независимо от Java solver.

Проверяет:

- GeoJSON `FeatureCollection`;
- обязательные поля выходных сущностей;
- уникальность ID;
- ровно один `variant_summary`;
- отсутствие NaN/Infinity;
- связность каждого ОКС до tie-in;
- совпадение endpoint с объявленным node_id;
- корректность tie-in на существующей сети/камере;
- суммарный расход на общих ветках;
- достаточный диаметр;
- стоимость и summary;
- hairpin-развороты около 180 градусов;
- спецпересечения дорог/трамвая под углом `>=45°`;
- запрет пересечения forbidden restrictions;
- наличие камер в точках ветвления;
- степень камеры `<=4`.

### 2. Smoke-тесты checker-а

Файл:

```text
scripts/benchmark_smoke_tests.py
```

Проверяют, что checker реально ловит намеренно испорченные результаты:

- удаленный сегмент;
- слишком маленький диаметр;
- hairpin;
- плохой угол дороги;
- duplicate ID;
- duplicate summary;
- NaN/Infinity;
- пересечение forbidden restriction.

### 3. Numeric benchmark

Файл:

```text
scripts/run_numeric_benchmarks.py
```

Проверяет числовые эталоны из пакета GPT-6 Pro:

- каталог диаметров;
- стоимости;
- score;
- shared-vs-separate templates;
- far tie-in wins;
- reconstruction intervals;
- RL reward arithmetic.

Последний результат:

```text
ARITHMETIC_CHECKS_PASSED
assertions_passed: 64
numeric_templates_checked: 7
reference_status: MATCH
```

### 4. GeoJSON benchmark suite

Файл:

```text
scripts/run_benchmark_suite.py
```

Гоняет solver через API:

```text
POST /api/trace
```

Потом запускает independent checker.

## Последний benchmark результат

Команда:

```bash
python scripts/run_benchmark_suite.py --include-official --timeout 420 --no-fail
```

Результат:

```text
contract: 4/5
decision: 4/5
```

Таблица:

| Кейс | Контракт | Ожидание решения | Статус |
|---|---:|---:|---|
| `00_official_dataset` | OK | OK | 17/17 ОКС подключены |
| `01_separate_pipes_moscow_zil` | OK | OK | separate pipes, 4/4 ОКС подключены |
| `02_shared_pipe_moscow_kommunarka` | OK | OK | shared pipe |
| `03_fifty_fifty_innopolis` | FAIL | FAIL | осталось 1 пересечение парка |
| `04_refusal_nizhny_novgorod_strelka` | OK | OK | economic refusal |

## Что осталось до полного алгоритма

### P0. Добить контрактную валидность

Остался один contract failure:

```text
03_fifty_fifty_innopolis:
FORBIDDEN_RESTRICTION_CROSSED
```

Причина:

```text
Текущий grid/dogleg/A* не строит достаточно хороший corridor graph,
чтобы гарантированно обойти парк на этом кейсе.
```

Что нужно:

- строить candidate graph вокруг препятствий;
- добавлять вершины обхода по границам forbidden polygons;
- искать k альтернативных маршрутов, а не один A* route;
- выбирать лучший валидный маршрут checker-ом.

### P1. Реализовать экономический отказ

Кейс:

```text
04_refusal_nizhny_novgorod_strelka
```

Раньше contract OK, но decision FAIL:

```text
solver подключает ОКС,
а expected_decision = refusal_candidate.
```

Что было нужно:

- сравнивать стоимость подключения со штрафом неподключения;
- если подключение дороже штрафа, разрешать `unconnected`;
- корректно экспортировать `unconnected_oks_ids` и `unconnected_penalty`.

Статус после правки 2026-09-19:

```text
В BaselineSolver добавлена проверка economic refusal по полной целевой функции S,
а не только по рублевой стоимости.
Если штраф неподключения дает меньший score, ОКС остается unconnected.
```

Проверено через Docker/API benchmark:

```text
04_refusal_nizhny_novgorod_strelka:
contract OK
decision OK
0/1 ОКС подключены
unconnected_penalty = 103000000
```

Дополнительная настройка:

```text
Economic refusal сделан консервативным:
отказ включается только если score маршрута заметно хуже score штрафа
по коэффициенту ECONOMIC_REFUSAL_SCORE_RATIO = 1.5.

Это сохраняет refusal для 04 и не отключает ОКС в 01_separate_pipes.
```

### P2. Перевести B1-lite в archive-selection

Сейчас B1-lite regret insertion есть как экспериментальная логика, но не default.

Перед этим желательно добавить явный режим задачи:

```text
ECONOMIC: отказ разрешён, если score лучше.
CONNECT_ALL_FIXTURE: отказ запрещён, если физический маршрут найден.
```

Это нужно, чтобы `04_refusal` оставался отказным, а `01_separate_pipes` не отключал часть ОКС, когда benchmark ожидает отдельные подключения.

Правильная схема:

```text
candidate archive = [
  greedy baseline,
  B1 regret insertion,
  B2 backbone candidates,
  later B3/R1 candidates
]

for candidate:
  export result
  run checker
  if valid:
    compute score

return best valid candidate
```

Так новые алгоритмы смогут улучшать результат, но не смогут случайно ухудшить production output.

### P3. Реализовать B2 backbone

Из пакета GPT-6 Pro:

```text
B2 = weighted centers + backbone trunk + side attachments
```

Что нужно:

- генерировать центры:
  - unweighted;
  - flow-weighted;
  - cost-weighted;
- строить trunk от tie-in к центру;
- подключать ОКС к позициям на trunk;
- пересчитывать flow/diameter along trunk;
- соблюдать max chamber degree.

### P4. Потом B3/R1

Только после B0/B1/B2 и надежного checker-а:

- B3 ALNS;
- R1 PPO policy;
- R2 RL-controller;
- X0 для маленьких сцен.

Пока к RL переходить рано: без archive/checker/candidate graph модель будет учиться на плохой среде.

## Итог

Текущий статус:

```text
Надежная валидация: да, уже есть.
Production algorithm: B0/B1-lite MVP.
Полный алгоритм из research-пакета: еще нет.
Benchmark progress: 5/5 contract, 4/5 decision.
```

Обновление 2026-09-19:

```text
RoutePlanner исправлен: endpoint-исключение для forbidden geometry теперь действует
только для restriction_type=oks. Парк/вода/social/prohibited больше не становятся
разрешенными только потому, что конец сегмента попал внутрь геометрии.

Проверка:
python scripts/run_benchmark_suite.py --include-official --timeout 420 --no-fail

Итог:
00_official_dataset: OK / OK, 17/17 ОКС
01_separate_pipes_moscow_zil: OK / OK, 4/4 ОКС
02_shared_pipe_moscow_kommunarka: OK / OK, 4/4 ОКС
03_fifty_fifty_innopolis: contract OK, decision FAIL, 2/4 ОКС
04_refusal_nizhny_novgorod_strelka: OK / OK, 0/1 ОКС
```

Попытка сделать B1-lite default:

```text
Полный regret insertion был временно включен как основной цикл.
Результат оказался хуже для production: большие кейсы начали упираться в таймаут,
а 03 всё равно оставался refusal_candidate.

Вывод: B1-lite нельзя просто включать вместо B0.
Его нужно запускать через candidate archive / portfolio-selection:
генерировать несколько вариантов, прогонять checker, затем выбирать лучший валидный.
```

Следующая инженерная задача:

```text
Сделать candidate graph вокруг препятствий и archive-selection.
```

После этого можно добить `03` без риска сломать `01/02/04`, затем переходить к полноценному B2/B3/R1.

## Обновление ТЗ 2026-09-19

Получен новый пакет:

```text
docs/spec/tz_update_2026_09_19/main_spec.pdf
docs/spec/tz_update_2026_09_19/technical_appendix_lct.docx
docs/spec/tz_update_2026_09_19/clarifications_lct.docx
docs/spec/tz_update_2026_09_19/TZ_UPDATE_REVIEW.md
data/tz_update_2026_09_19/corrected_dataset.geojson
```

Ключевое изменение модели:

```text
Актуальным считается техническое приложение и разъяснения.
Реконструкция существующей сети исключена.
Отдельный output object_type=tie_in больше не формируется.
Присоединение новой сети к существующей выполняется через heat_chamber.
Экономический отказ от технически доступного подключения запрещен.
Расход читается из oks_connection_point.flow_tph.
railway является запретным ограничением.
```

Что изменено в реализации:

```text
GeoJsonWriter больше не экспортирует tie_in.
BaselineSolver при присоединении к существующей трубе создает новую heat_chamber.
BaselineSolver при присоединении к существующей камере начисляет existing_chamber_tie_in_cost.
Solution считает construction_cost как трубы + новые камеры + врезки в существующие камеры.
calculated_cost = construction_cost + unconnected_penalty.
Экономический отказ отключен; unconnected остается только для случая no route found.
DiameterCatalog выбирает минимальный ДУ, который проходит и по расходу, и по длине.
RoutePlanner считает railway запретным ограничением.
RoutePlanner строит STRtree-индексы ограничений и сегментов спецпересечений один раз на расчет.
RoutePlanner кэширует результаты одинаковых запросов маршрута внутри одного расчета.
BaselineSolver сравнивает четыре порядка подключения и выбирает полный вариант с минимальным score.
benchmark_checker переведен на новый output contract без tie_in.
run_benchmark_suite проверяет пороги длины, стоимости, score и времени из manifest.
```

Проверка нового скорректированного датасета:

```text
input: data/tz_update_2026_09_19/corrected_dataset.geojson
result: results/tz_update_2026_09_19/result.geojson
report: results/tz_update_2026_09_19/checker_report.json

status: VALID
connected_oks_count: 17/17
connected_flow_tph: 488.72
new_segment_count: 17
new_chamber_count: 12
tie_in_count: 0
existing_chamber_tie_in_count: 1
selected_ordering: east_to_west
new_network_length: 1568.46
construction_cost: 234403829.94
chamber_construction_cost: 40000000.00
existing_chamber_tie_in_cost: 5000000.00
unconnected_penalty: 0
score: 11.27
violations: 0
max_node_degree: 4
elapsed_seconds_before_spatial_index: 352.43
elapsed_seconds_after_portfolio: 7.60
speedup: 46.4x
```

Оставшиеся ограничения MVP:

```text
Пока не реализован точный габаритный отступ по ДУ для всех ограничений.
Финальный подход к собственному ОКС строится прямым участком через ближайшую точку границы; повторный вход и поворот до выхода из минимальной 5-метровой зоны отклоняются независимым checker.
technical_node для разбиения специальных проходов пока не генерируется.
Полный датасет теперь считается примерно за 10 секунд; следующий шаг по качеству — candidate graph / archive-selection.
Глобальная оптимальность не доказана: это валидный portfolio baseline, а не финальный алгоритм B2/B3/RL.
```

После ужесточения endpoint-правила контрольный `PORTFOLIO`-прогон исходного датасета имеет
`0` нарушений, `0` запрещенных пересечений и `0` разворотов на 180 градусов. Подключены
`14/17` точек; для точек `2`, `5` и `10` текущий поиск не нашел допустимый маршрут после
обязательного выхода через ближайшую границу. Они остаются задачей улучшения поиска, а не
основанием ослаблять проверку.

## GeoJSON Viewer

Инструмент визуализации перенесен внутрь приложения:

```text
src/main/resources/static/viewer/index.html
src/main/resources/static/viewer/styles.css
src/main/resources/static/viewer/app.js
http://localhost:8080/viewer/
```

Viewer читает произвольные GeoJSON FeatureCollection, накладывает исходные данные и результат, управляет слоями,
показывает variant_summary и свойства выбранного объекта. Из интерфейса можно вызвать `/api/trace` и скачать ответ.

Проверено на полном скорректированном датасете: расчет из UI завершился с `17/17`, длиной `1568.46 м`, стоимостью
`234403829.94` и `score=11.27`. Также проверены desktop/mobile, переключение слоев, инспектор, zoom/fit и ошибка API.
