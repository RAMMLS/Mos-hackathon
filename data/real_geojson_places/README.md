# Real GeoJSON Places

Здесь должны лежать только реальные GeoJSON-выгрузки местности из OpenStreetMap.
Никаких синтетических тепловых труб, источников, камер, точек подключения или
SVG-картинок в этом наборе не создаём.

## Цель

Собрать 400 реальных мест в РФ:

- `separate_pipes` - 100 мест, где геометрия похожа на случай для отдельных веток;
- `shared_pipe` - 100 мест, где геометрия похожа на случай для общего ствола;
- `fifty_fifty` - 100 пограничных мест;
- `refusal` - 100 мест с сильными барьерами, где надо проверять отказ.

Категория - исследовательская корзина по метрикам реальной геометрии, а не
официальный ответ теплосетевой задачи.

## Сначала посмотреть план без сети

```bash
python3 scripts/download_real_geojson_places.py --plan-only
```

Это создаст:

```text
data/real_geojson_places/candidate_windows.json
```

## Скачать 400 настоящих GeoJSON

Лучше запускать без VPN, потому что Overpass часто режет VPN/IP по `429` и
иногда отдаёт `504`.

Сначала проверь, какие endpoint'ы отвечают:

```bash
python3 scripts/download_real_geojson_places.py --probe --request-timeout 12
```

Пробная загрузка одного места в каждую категорию:

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --target-per-category 1 \
  --max-candidates 120 \
  --request-timeout 12 \
  --overpass-timeout 12 \
  --window-m 500 \
  --verbose
```

Массовая загрузка:

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --target-per-category 100 \
  --max-candidates 1600 \
  --request-timeout 12 \
  --overpass-timeout 12 \
  --window-m 500 \
  --sleep 8 \
  --rate-limit-sleep 120
```

Если нужен конкретный endpoint:

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --endpoint https://lz4.overpass-api.de/api/interpreter \
  --endpoint https://overpass-api.de/api/interpreter \
  --sleep 8 \
  --rate-limit-sleep 120
```

Если `--probe` показывает, что живой только `z.overpass-api.de`, запускай так:

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --target-per-category 100 \
  --max-candidates 1600 \
  --endpoint https://z.overpass-api.de/api/interpreter \
  --request-timeout 12 \
  --overpass-timeout 12 \
  --window-m 500 \
  --sleep 8 \
  --rate-limit-sleep 120
```

Если нужны крупные водные/парковые relation-мультиполигоны:

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --include-relations
```

## Результат

```text
data/real_geojson_places/
  separate_pipes/*.geojson
  shared_pipe/*.geojson
  fifty_fifty/*.geojson
  refusal/*.geojson
  index.json
  errors.json
  raw_osm/*.json
```

Каждый `*.geojson` содержит только реальные OSM feature-объекты:

- `building`;
- `road`;
- `major_road`;
- `railway`;
- `water`;
- `green`.

В `metadata` файла лежат bbox, город, категория и метрики отбора.
