# Реализация RL-алгоритмов R1 и R2

Дата финального прогона: 2026-09-19.

## Итог

Оба RL-алгоритма теперь имеют отдельные исполняемые классы, отдельные модели и проходят API -> GeoJSON ->
независимый checker. `R2` больше не возвращает `NOT_TRAINED`, а `R1` больше не является alias выбора целого
baseline-алгоритма.

На основном датасете оба получили `VALID`, подключили 17/17 ОКС и вернули лучший найденный проект `S=10.93`.
Прямой `B2-C-J` остается быстрее, поэтому наличия RL-контура недостаточно для заявления о пользе обучения.

## R1: concrete-action PPO

Runtime-класс: `src/main/java/ru/moshackathon/heatnetwork/solver/R1Solver.java`.

Policy loader: `src/main/java/ru/moshackathon/heatnetwork/solver/R1ConcretePolicy.java`.

Модель: `models/r1_v2/model.json`.

Hash весов:

```text
dc9f508a1639744c1f37782156080560c4fff5e173f18650794a5e9439831b24
```

Реализованный каталог:

| Действие | Конкретные параметры | Выполнение |
|---|---|---|
| CONNECT | oks_id, route mode | Генерируется, когда в состоянии есть неподключенный ОКС |
| ATTACH | oks_id, target_node, route | Принудительное присоединение к выбранному узлу |
| MERGE | два ID группы, junction template | Перестройка группы с разрешенной врезкой внутрь новой трубы |
| BUILD_BACKBONE | B2-U/Q/C/C-J, order template | Перепроектирование всей выбранной группы |
| REATTACH | oks/subtree root, target_node, route | Замена назначения родителя с полным пересчетом |
| STOP | без параметров | Возврат лучшего проекта из архива |

Каждый шаг имеет стабильное содержательное имя, action features, mask и payload. Применение транзакционно:
неполное или аварийное действие откатывается, текущий проект не повреждается. После успешного шага заново
строятся маршруты и пересчитываются потоки, диаметры, камеры, стоимость и `S`. Best archive не ухудшается.

PPO обучается командой:

```text
python -m scripts.r1.train_concrete --epochs 750
```

Training pipeline хранит неизменный mask/action snapshot, использует clipped PPO и награду по улучшению best
archive. Model manifest фиксирует схемы признаков/действий, dataset hash, seed и hash весов.

На основном датасете policy выполнила 12 backbone-вариантов, затем конкретные ATTACH/REATTACH/MERGE-кандидаты.
Лучшим стало действие `BUILD_BACKBONE:B2-C-J:NEAR`; конечный результат `S=10.93`.

## R2: RL-контроллер destroy/repair

Runtime-класс: `src/main/java/ru/moshackathon/heatnetwork/solver/R2Solver.java`.

Policy loader: `src/main/java/ru/moshackathon/heatnetwork/solver/R2Policy.java`.

Модель: `models/r2_v1/model.json`.

Hash весов:

```text
e8fc9db069055437d4922f89dcb60e902104a5ef82bf2542b618c0847a4919f4
```

Каталог пар операторов:

```text
RANDOM_20 + GREEDY
GEOGRAPHIC_25 + GREEDY
HIGH_FLOW_25 + REGRET
BACKBONE_40 + JUNCTION
SUBTREE_25 + REGRET
STOP
```

Начальные priors обучаются только на независимо проверенных переходах. Внутри запуска R2 обновляет ценность
выбранного оператора по фактическому улучшению best archive. Финальная последовательность на основной сцене:

```text
SUBTREE_25+REGRET -> RANDOM_20+GREEDY -> HIGH_FLOW_25+REGRET ->
GEOGRAPHIC_25+GREEDY -> BACKBONE_40+JUNCTION -> STOP
```

Лучший вариант найден оператором `BACKBONE_40+JUNCTION`, `S=10.93`.

## Общий бенчмарк

Вход: `data/tz_update_2026_09_19/corrected_dataset.geojson`.

| Алгоритм | Статус | ОКС | Длина, м | Стоимость, руб. | S | Время, с | Нарушения |
|---|---|---:|---:|---:|---:|---:|---:|
| B2-C-J | VALID | 17/17 | 1504.95 | 228 974 801.11 | **10.93** | 16.466 | 0 |
| R1 concrete PPO | VALID | 17/17 | 1504.95 | 228 974 801.11 | **10.93** | 23.079 | 0 |
| R1-PILOT control | VALID | 17/17 | 1504.95 | 228 974 801.11 | **10.93** | 58.004 | 0 |
| R2 controller | VALID | 17/17 | 1504.95 | 228 974 801.11 | **10.93** | 58.961 | 0 |
| B2-U/Q/C | VALID | 17/17 | 1580.85 | 231 633 766.79 | 11.23 | 14.6-15.2 | 0 |
| B3 | VALID | 17/17 | 1580.85 | 231 633 766.79 | 11.23 | 20.386 | 0 |
| PORTFOLIO | VALID | 17/17 | 1568.46 | 234 403 829.94 | 11.27 | 8.882 | 0 |
| B1 | VALID | 17/17 | 2357.41 | 323 288 458.61 | 16.12 | 41.535 | 0 |
| B0-GRID | INVALID | 17/17 | 5577.30 | 607 852 791.91 | 33.75 | 56.266 | 1 |
| B0-CORRIDOR | INVALID | 17/17 | 5549.76 | 609 361 783.33 | 33.71 | 60.530 | 2 |
| X0 | NOT_APPLICABLE | - | - | - | - | 0.051 | - |

На малой junction-сцене `R1`, `R2`, `B3`, `PORTFOLIO` и применимый `X0` совпали на `S=0.70`, все результаты
валидны. То есть RL не проиграл точному контролю на текущем конечном shortlist, но этот один пример не является
доказательством обобщения.

## Конкретные недостатки

| Метод | Недостаток | Этап |
|---|---|---|
| R1 | Модель обучена на 5 train и 3 validation сценах; этого мало для production-обобщения | Нужен большой scene generator и закрытый test split |
| R1 | В shortlist преобладают детерминированные corridor-шаблоны; policy не может создать отсутствующий коридор | Расширение candidate graph и quota/diversity ablation |
| R1 | Graph state пока кодируется агрегатами и action features, без 3-layer GNN | Архитектурный эксперимент после расширения данных |
| R2 | Repair-операторы ограничены текущими greedy/regret/backbone реализациями | Нужны дополнительные edge/subtree templates и равный evaluator budget |
| R2 | Обученные priors получены на малом наборе; online update не доказывает перенос | Несколько seeds и независимый test split |
| Оба | На основной сцене качество равно прямому B2-C-J, но время больше | Пока не заменяют B2-C-J в production default |

## Проверки

- Docker/Maven build: PASS.
- R1/PPO/model tests: 15/15 PASS.
- R1 и R2 на основном датасете: VALID, 17/17, 0 нарушений.
- R1 и R2 на малой сцене: VALID, совпадение с X0 по `S=0.70`.
- Junction mutation/regression tests: PASS.
- Frozen B2-C hash control: PASS.
- Checker smoke mutations: PASS.
- Numeric reference: 64 проверки, MATCH.

Машинные результаты:

```text
results/all-algorithms-rl-v2/summary.json
results/all-algorithms-rl-v2/summary.md
results/all-algorithms-small-rl-v2/summary.json
results/all-algorithms-small-rl-v2/summary.md
results/r1-v2/training-report.json
results/r2/training-report.json
```

## Практический вывод

Полные runtime/training/inference/checker контуры R1 и R2 теперь присутствуют и запускаются. По текущему бенчу
лучшим практическим выбором остается `B2-C-J`: тот же `S=10.93` за меньшее время. R1 и R2 следует улучшать через
новые независимые сцены и более сильный общий candidate space, а не объявлять победителями только потому, что
они используют обучение.
