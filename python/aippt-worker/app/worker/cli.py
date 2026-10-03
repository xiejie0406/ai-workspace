import argparse
import asyncio
import json
import logging
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="若依演示文稿算法 Worker")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("health", help="本地算法就绪，不连接中心或Provider")
    run = sub.add_parser("run"); run.add_argument("--once", action="store_true")
    regression = sub.add_parser("regression"); regression.add_argument("--output", default="var/regression")
    convert = sub.add_parser("prepare-import"); convert.add_argument("--snapshot", required=True); convert.add_argument("--owners", required=True)
    convert.add_argument("--objects", required=True); convert.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "health":
        from app.worker.catalog import catalog_readiness
        from app.core.config import get_settings
        cfg = get_settings()
        print(json.dumps({"status": "ready", **catalog_readiness(), "provider_configured": bool(cfg.llm_api_key),
                          "center_configured": bool(cfg.worker_key.get_secret_value()),
                          "image_enabled": cfg.image_enabled}, ensure_ascii=False)); return
    if args.command == "prepare-import":
        from app.worker.migration import prepare_import
        print(json.dumps(prepare_import(Path(args.snapshot), Path(args.owners), Path(args.objects), Path(args.output)), ensure_ascii=False)); return
    if args.command == "regression":
        from app.regression.runner import run_regression, format_report
        from app.regression.corpus import load_corpus_decks
        from app.domain.theme import load_themes
        from app.render.pptx import render_deck_to_pptx
        output = Path(args.output); output.mkdir(parents=True, exist_ok=True)
        report = run_regression()
        (output / "regression-report.json").write_text(report.model_dump_json(indent=2), encoding="utf-8")
        for density, deck in load_corpus_decks().items():
            for theme in sorted(load_themes()):
                (output / f"{density}-{theme}.pptx").write_bytes(render_deck_to_pptx(deck, theme).getvalue())
        print(format_report(report))
        raise SystemExit(0 if report.passed else 1)
    from app.core.config import get_settings
    from app.worker.runner import Worker
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    try: asyncio.run(Worker(get_settings()).run(once=args.once))
    except KeyboardInterrupt: pass


if __name__ == "__main__": main()
