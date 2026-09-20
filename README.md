# Mos-hackathon

Backend MVP для задачи ЛЦТ 2026 "Сервис моделирования трасс подключения к тепловым сетям".

## Что уже закреплено

- Материалы кейса лежат в `data/` и `docs/`.
- Выжимка встречи с постановщиками: `docs/meeting_takeaways.md`.
- Порядок реализации MVP: `docs/MVP_IMPLEMENTATION_ORDER.md`.
- Первый Java/Spring Boot baseline принимает GeoJSON и возвращает один рассчитанный `FeatureCollection`.

## Запуск

Требуемый стек для проверки: Java 11, Maven, Docker Compose.

```bash
docker compose up --build
```

API:

```bash
curl -F "file=@data/tz_update_2026_09_19/corrected_dataset.geojson" \
  http://localhost:8080/api/trace -o result.geojson
```

Выбор алгоритма передается query-параметром, список статусов доступен отдельным endpoint:

```bash
curl -F "file=@data/tz_update_2026_09_19/corrected_dataset.geojson" \
  "http://localhost:8080/api/trace?algorithm=B2-C" -o result.geojson
curl http://localhost:8080/api/trace/algorithms
```

## GeoJSON Viewer

Интерактивный просмотрщик входных данных и результата доступен после запуска Docker:

```text
http://localhost:8080/viewer/
```

Viewer читает произвольные `FeatureCollection` из `.geojson`/`.json`, накладывает вход и результат, управляет слоями,
показывает `variant_summary` и свойства выбранной геометрии. Кнопка «Рассчитать трассу» отправляет загруженный вход
в `/api/trace`, отображает новый результат и позволяет скачать его обратно в GeoJSON.

## Benchmark / checker

### Numeric benchmark specs

Пакет `NETWORK_OPTIMIZATION_RESEARCH_AND_IMPLEMENTATION.zip` закреплён как спецификация стенда:

- машинные JSON: `data/benchmark_specs/`;
- текстовые документы: `docs/benchmark_specs/`.

Важное ограничение пакета: `benchmark_cases.json` содержит 61 задание на будущие геометрические фикстуры, но не сами
готовые GeoJSON-входы/выходы. Исполняемая часть пакета сейчас — 7 числовых эталонов, которые проверяют каталог,
стоимости, score, реконструкцию интервалов и несколько контрольных экономических сравнений.

```bash
python scripts/run_numeric_benchmarks.py
```

Отчёт сохраняется в `results/numeric-benchmarks/report.json`.

### GeoJSON checker

Независимый checker проверяет уже готовый `result.geojson`, не строя маршруты заново. Он подтверждает пути от каждой
точки подключения до существующей или построенной тепловой камеры, пересчитывает расходы по общим веткам, проверяет диаметры по каталогу, ловит резкие
развороты трассы примерно на 180 градусов, проверяет угол спецпересечения дорог/трамвайных путей `>= 45°` и сверяет
итоговые стоимости.

```bash
python scripts/benchmark_checker.py \
  --input "data/tz_update_2026_09_19/corrected_dataset.geojson" \
  --result result.geojson \
  --out results/benchmark-current.json \
  --run-id current-tree-mvp
```

Проверка самого checker-а на намеренно испорченных результатах:

```bash
python scripts/benchmark_smoke_tests.py
```

Основной manifest обновлённого ТЗ лежит в `data/tz_update_2026_09_19/benchmark_cases.json`. Старые research-кейсы
сохранены в `data/research_examples/` как материал для последующей миграции на новый контракт.

Посмотреть состав кейсов:

```bash
python scripts/run_benchmark_suite.py --list
```

Прогнать основной набор через поднятый API:

```bash
python scripts/run_benchmark_suite.py
```

Старые research-кейсы можно запустить явно:

```bash
python scripts/run_benchmark_suite.py --manifest data/research_examples/index.json
```

Результаты сохраняются в `results/benchmark-suite/summary.md`, `summary.json` и в отдельных папках кейсов.

Сравнение всех алгоритмов из исследовательского списка:

```bash
python scripts/run_algorithm_benchmark.py
```

Отчет сохраняется в `results/algorithm-benchmark/summary.md`. Зафиксированный разбор реализации и ограничений:
`docs/algorithm_benchmark_report_2026_09_19.md`.

Контрольный прогон после пространственной индексации и портфеля порядков подключения: `VALID`, подключено `17/17` ОКС,
`new_network_length = 1568.46 м`, `calculated_cost = 234403829.94`, `score = 11.27`, время полного расчёта `7.6 с`.
До оптимизации baseline давал `2007.15 м`, `292118047.52`, `score = 14.20` за `352.4 с`. Manifest автоматически
запрещает откат качества и время больше `60 с`.

## Текущие алгоритмы

Production default по-прежнему работает как портфель четырех детерминированных tree-построений. Кроме него API
поддерживает `B0-GRID`, `B0-CORRIDOR`, `B1`, `B2-U`, `B2-Q`, `B2-C`, `B2-C-J`, `B3` и ограниченный малый `X0`.
`R1` реализован как отдельная PPO-среда над параметризованными действиями `CONNECT`, `ATTACH`, `MERGE`,
`BUILD_BACKBONE`, `REATTACH`, `STOP`. Действия содержат ID ОКС, целевого узла/группы и варианта маршрута,
применяются транзакционно и проходят полный пересчет. `R1-PILOT` сохранен как исторический контроль над целыми
solver-предложениями. `R2` реализован отдельно как обученный контроллер пар destroy/repair с собственным
model artifact и online-обновлением ценности операторов.

```bash
python -m unittest scripts.r1.test_core -v
python -m scripts.r1.build_dataset
python -m scripts.r1.train --epochs 500
python -m scripts.r1.train_concrete --epochs 750
python -m scripts.r2.train
python -m scripts.r1.check_java_parity
python scripts/junction_regression_tests.py
python scripts/verify_control_baseline.py
curl -F "file=@data/tz_update_2026_09_19/corrected_dataset.geojson" \
  "http://localhost:8080/api/trace?algorithm=R1-PILOT" -o r1-pilot.geojson
curl -F "file=@data/tz_update_2026_09_19/corrected_dataset.geojson" \
  "http://localhost:8080/api/trace?algorithm=R1" -o r1.geojson
```

Подробный статус: `docs/r1_pilot_report_2026_09_19.md`.
Реализация развилок внутри новой трубы: `docs/junction_in_edge_report_2026_09_19.md`.

Общее ядро:

1. читает входной GeoJSON;
2. берёт расход из `oks_connection_point.flow_tph`;
3. ищет кандидаты врезки в существующие камеры и участки сети;
4. применяет правило 10 м до существующей камеры;
5. строит четыре детерминированных варианта порядка подключения и выбирает лучший по `score`;
6. допускает присоединение к уже построенной новой ветке;
7. проверяет прямой путь, затем dogleg и A*; ограничения индексируются пространственно, а маршруты кэшируются;
8. выбирает диаметр по расходу и длине;
9. считает стоимость новых участков, врезок, новых камер и штрафов;
10. отдаёт `heat_network`, `heat_chamber`, при необходимости `technical_node`, и один `variant_summary`;
11. не создаёт отдельный `tie_in`: по обновлённому ТЗ подключение оформляется камерой.

Ограничения текущего семейства: коридорная геометрия и B3 пока реализованы в `lite`-режиме, нет обязательной
3D-глубины и реконструкции существующей сети по скрытым промышленным данным. RL-контуры исполняются полностью,
но для production-вывода нужны существенно больше независимых training/test-сцен; B3/R2 пока используют
ограниченный набор repair-шаблонов и не доказывают глобальную оптимальность.
