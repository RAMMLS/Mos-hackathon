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
curl -F "file=@data/Датасет/!!!_Датасет.geojson" http://localhost:8080/api/trace -o result.geojson
```

Swagger UI после запуска:

```text
http://localhost:8080/swagger-ui.html
```

Визуализация исследовательского GeoJSON-корпуса:

```text
http://localhost:8080/visualization/
```

Контейнер читает 400 реальных GeoJSON из `data/real_geojson_places` через read-only
volume. Индекс доступен по `GET /api/visualization/datasets`, отдельный файл — по
`GET /api/visualization/datasets/{category}/{fileName}`.

## Текущий алгоритм

Первый MVP работает в baseline-режиме:

1. читает входной GeoJSON;
2. берёт расход из `oks_connection_point.flow_tph`;
3. ищет кандидаты врезки в существующие камеры и участки сети;
4. применяет правило 10 м до существующей камеры;
5. строит независимый 2D-маршрут для каждой точки подключения;
6. обходит запрещённые ограничения простым dogleg fallback;
7. выбирает диаметр по расходу и длине;
8. считает стоимость новых участков, врезок, новых камер и штрафов;
9. отдаёт `heat_network`, `tie_in`, `heat_chamber` и `variant_summary`.

Ограничения текущего baseline: нет объединения веток, нет LNS/ALNS, нет обязательной 3D-глубины, нет реконструкции существующей сети по скрытым промышленным данным.
