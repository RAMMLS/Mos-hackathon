#!/usr/bin/env python3
"""Build the audited dual-entry algorithm report from persisted checker outputs."""

import json
import re
import statistics
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNS = ROOT / "results/all-algorithms-dual-entry-test-2026-09-27/runs"
SMOKE = ROOT / "results/all-algorithms-dual-entry-smoke-v4-2026-09-27"
TIE = ROOT / "results/tie-in-stress-dual-budget-fix-2026-09-27"
OUTPUT = ROOT / "docs/ALGORITHM_DUAL_ENTRY_BENCHMARK_REPORT_2026_09_27.md"
ALGORITHMS = [
    "B0-GRID", "B0-CORRIDOR", "B1", "B2-U", "B2-Q", "B2-C", "B2-C-J",
    "B3", "R1-PILOT", "R1", "R2", "X0", "X1", "X2", "PORTFOLIO",
]
EXCLUDED_SCENE = "moscow_center_+3_+0_40c3dc63_tie_in_stress"


def algorithm_dir(name):
    return name.lower().replace("-", "_")


def load_result(scene_dir, algorithm):
    directory = scene_dir / algorithm_dir(algorithm)
    report_path = directory / "checker-report.json"
    result_path = directory / "result.geojson"
    if not report_path.exists() or not result_path.exists():
        return None
    report = json.loads(report_path.read_text(encoding="utf-8"))
    result = json.loads(result_path.read_text(encoding="utf-8"))
    summary = next(
        ((feature.get("properties") or {}) for feature in result.get("features", [])
         if (feature.get("properties") or {}).get("object_type") == "variant_summary"),
        {},
    )
    diagnostics = summary.get("diagnostics") or []
    elapsed_ms = None
    selected_entry = None
    origin = None
    for diagnostic in diagnostics:
        match = re.search(r"elapsed_ms=(\d+)", str(diagnostic))
        if match:
            elapsed_ms = int(match.group(1))
        if str(diagnostic).startswith("entry_strategy_selected="):
            selected_entry = str(diagnostic).split("=", 1)[1]
        if str(diagnostic).startswith("algorithm=") and origin is None:
            origin = str(diagnostic).split("=", 1)[1]
    geometry = report.get("geometry_metrics") or {}
    costs = report.get("recomputed_cost_components") or {}
    return {
        "valid": bool(report.get("contract_valid")),
        "connected": geometry.get("connected_oks_count") or 0,
        "total": geometry.get("total_oks_count") or 0,
        "score": costs.get("score"),
        "cost": costs.get("calculated_cost"),
        "length": costs.get("new_network_length"),
        "violations": len(report.get("violations") or []),
        "elapsed_s": elapsed_ms / 1000.0 if elapsed_ms is not None else None,
        "entry": selected_entry,
        "origin": origin,
    }


def fmt(value, digits=2):
    if value is None:
        return "-"
    return f"{value:.{digits}f}"


def main():
    scene_dirs = sorted(
        path for path in RUNS.iterdir()
        if path.is_dir() and path.name != EXCLUDED_SCENE
    )
    records = []
    for scene_dir in scene_dirs:
        for algorithm in ALGORITHMS:
            item = load_result(scene_dir, algorithm)
            if item is not None:
                records.append({"scene": scene_dir.name, "algorithm": algorithm, **item})

    aggregate = []
    for algorithm in ALGORITHMS:
        items = [item for item in records if item["algorithm"] == algorithm]
        wins = 0
        for scene_dir in scene_dirs:
            scene_items = [item for item in records if item["scene"] == scene_dir.name and item["valid"]]
            if not scene_items:
                continue
            best_coverage = max(item["connected"] for item in scene_items)
            best_score = min(
                item["score"] for item in scene_items
                if item["connected"] == best_coverage and item["score"] is not None
            )
            own = next((item for item in scene_items if item["algorithm"] == algorithm), None)
            if own and own["connected"] == best_coverage and abs(own["score"] - best_score) <= 0.005:
                wins += 1
        valid = [item for item in items if item["valid"]]
        entry_counts = Counter(item["entry"] or "UNKNOWN" for item in valid)
        aggregate.append({
            "algorithm": algorithm,
            "applicable": len(items),
            "valid": len(valid),
            "full": sum(item["connected"] == item["total"] and item["total"] > 0 for item in valid),
            "wins": wins,
            "coverage": statistics.mean(item["connected"] / item["total"] for item in valid if item["total"]),
            "score": statistics.mean(item["score"] for item in valid if item["score"] is not None),
            "elapsed": statistics.mean(item["elapsed_s"] for item in valid if item["elapsed_s"] is not None),
            "entry": ", ".join(f"{key}:{value}" for key, value in sorted(entry_counts.items())),
        })

    scene_rows = []
    for scene_dir in scene_dirs:
        items = [item for item in records if item["scene"] == scene_dir.name and item["valid"]]
        best_coverage = max(item["connected"] for item in items)
        winner = min(
            (item for item in items if item["connected"] == best_coverage),
            key=lambda item: (item["score"], item["algorithm"]),
        )
        scene_rows.append((scene_dir.name, winner))

    smoke_summary = (SMOKE / "summary.md").read_text(encoding="utf-8")
    tie_summary = (TIE / "summary.md").read_text(encoding="utf-8")
    lines = [
        "# Полный отчёт: алгоритмы после правила свободного финального входа в ОКС",
        "",
        "Дата: 2026-09-27",
        "",
        "## Итог",
        "",
        "Production-алгоритм: **PORTFOLIO**. На контрольном Иннополисе он выбирает X2 и даёт "
        "`6/6`, `434.87 м`, `58.97 млн руб.`, `score=2.96`, `0` нарушений. На больших или "
        "тяжёлых сценах главным надёжным incumbent остаётся B3/R2, но полнота подключения "
        "ограничивается генератором допустимых маршрутов.",
        "",
        "Главный отрицательный вывод: текущий **R1 нельзя использовать самостоятельно**. После "
        "изменения endpoint/action space он систематически теряет покрытие и требует переобучения. "
        "R2 надёжнее, потому что сохраняет B3 incumbent.",
        "",
        "## Реализованные изменения",
        "",
        "1. Для каждого алгоритма добавлены два режима: `DIRECT_ALLOWED` и `PORTAL_ONLY`.",
        "2. Финальный участок может пересечь собственное здание с любой стороны; транзит через ОКС запрещён.",
        "3. ОКС больше не используется как родитель/транзитный узел: общие ветви соединяются через exterior junction.",
        "4. B2/B3/R2/X0 переведены на junction-safe построение дерева.",
        "5. Общий бюджет делится между двумя режимами, поэтому один режим не может вытеснить другой.",
        "6. B3 сначала сохраняет сильный B2-U incumbent и только затем запускает дорогой repair.",
        "7. Route cache поднят до `route-cache-v4-dual-entry-strategy`.",
        "",
        "## Методика",
        "",
        f"- Основной сравнимый срез: `{len(scene_dirs)}` полностью завершённых test-сцен, "
        f"`{len(records)}` применимых запусков.",
        "- На сценах свыше 6 ОКС X0/X1/X2 ожидаемо имеют `NOT_APPLICABLE` и не считаются ошибкой.",
        "- Ранжирование: сначала число подключённых ОКС, затем score при равном покрытии.",
        "- Каждый сохранённый результат независимо проверен `benchmark_checker.py`.",
        "- Восьмая tie-in stress сцена была перезапущена отдельно после найденного и исправленного "
        "дефекта budget monopolization; её результаты приведены отдельно и не смешаны со средними.",
        "",
        "## Агрегат по завершённым test-сценам",
        "",
        "| Алгоритм | VALID/применимо | Полное подключение | Победы | Среднее покрытие | Mean score | Mean solver, с | Выбранный вход |",
        "|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    ranking = sorted(aggregate, key=lambda item: (-item["full"], -item["wins"], -item["coverage"], item["score"]))
    for item in ranking:
        lines.append(
            f"| {item['algorithm']} | {item['valid']}/{item['applicable']} | {item['full']}/{item['applicable']} | "
            f"{item['wins']} | {100 * item['coverage']:.1f}% | {item['score']:.3f} | "
            f"{item['elapsed']:.2f} | {item['entry']} |"
        )

    lines.extend([
        "",
        "## Победители по сценам",
        "",
        "| Сцена | Победитель | Покрытие | Score | Время solver, с |",
        "|---|---|---:|---:|---:|",
    ])
    for scene, winner in scene_rows:
        lines.append(
            f"| `{scene}` | {winner['algorithm']} | {winner['connected']}/{winner['total']} | "
            f"{fmt(winner['score'])} | {fmt(winner['elapsed_s'])} |"
        )

    lines.extend([
        "",
        "## Недостатки конкретных алгоритмов",
        "",
        "| Семейство | Что умеет | Конкретный недостаток | Следующее улучшение |",
        "|---|---|---|---|",
        "| B0-GRID / B0-CORRIDOR | Надёжный независимый baseline | Не строит общий ствол; на tie-in stress было 0/8 | Использовать только как fallback и lower-level route probe |",
        "| B1 | Regret-порядок без незаконного транзита через ОКС | После запрета consumer-as-parent почти не разделяет сеть | Добавить regret по exterior junction-кандидатам |",
        "| B2-U/Q/C | Быстро создаёт общий backbone | Разные веса часто дают одинаковую топологию и тратят бюджет повторно | Дедупликация topology hash и адаптивный выбор medoid seed |",
        "| B2-C-J | Законные junction внутри существующей новой трубы | Не помог на нескольких geometry-limited сценах | Расширить позиции junction и разрешить совместный repair 2-4 ОКС |",
        "| B3 | Самый устойчивый standalone heuristic | Повторно оценивает эквивалентные seeds; без новых коридоров не повышает покрытие | Candidate dedup, negative-path cache, региональный topology repair |",
        "| R1-PILOT | Обычно сохраняет B3 seed | Почти не даёт улучшений поверх incumbent | Не считать production-RL; использовать как ablation |",
        "| R1 | Иногда улучшает малую сцену | Систематически теряет покрытие на stress-сценах после изменения action space | Переобучить на v4 candidates; всегда держать certified incumbent |",
        "| R2 | Сохраняет B3 и безопасно пробует repair | В измерениях редко улучшает B3 | Обучить оператор на реальных выигрышах region repair |",
        "| X0/X1 | Контроль перебора порядка/родительских деревьев | Точен только относительно текущего route shortlist, не непрерывной геометрии | Использовать как oracle для малых сцен, не как production |",
        "| X2 | Лучший результат на Иннополисе | Ограничен 6 ОКС и дорог; не помогает, если нужного ребра нет в графе | Больше boundary/visibility candidates и декомпозиция регионов |",
        "| PORTFOLIO | Лучший безопасный выбор между семействами | Наследует runtime всех веток и потолок route generator | Адаптивный dispatcher, ранняя остановка дубликатов, новый geometry layer |",
        "",
        "## Главный технический потолок",
        "",
        "На нескольких сценах B2, B3, R2, X0, X1 и X2 дают одинаковое покрытие. Это означает, "
        "что дальнейший перебор дерева не поможет: допустимого ребра нет в candidate graph. Следующий "
        "приоритет для качества: boundary-aware visibility graph, расширенный long-range A*, несколько "
        "порталов на каждую сторону здания, negative-path cache и локальная совместная перестройка 2-4 ОКС.",
        "",
        "## Контроль Иннополиса: все 15 алгоритмов",
        "",
        smoke_summary,
        "",
        "## Tie-in stress после исправления бюджета",
        "",
        tie_summary,
        "",
        "## Ограничения выводов",
        "",
        "- Это инженерный benchmark текущего deterministic test-среза, не доказательство глобальной оптимальности.",
        "- Средний score между сценами с разным числом ОКС вторичен; покрытие всегда важнее.",
        "- R1 обучался до изменения пространства допустимых endpoint/junction действий, поэтому его текущий результат честно считается устаревшим.",
        "- Для окончательной статистики после следующего улучшения route generator нужен повтор 112-scene benchmark.",
    ])
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(OUTPUT)


if __name__ == "__main__":
    main()
