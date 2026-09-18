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

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --target-per-category 100 \
  --max-candidates 1600
```

Если нужен конкретный endpoint:

```bash
python3 scripts/download_real_geojson_places.py \
  --download \
  --endpoint https://overpass-api.de/api/interpreter
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
