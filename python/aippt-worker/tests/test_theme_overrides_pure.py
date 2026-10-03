import uuid

from collections.abc import AsyncGenerator

import pytest

from app.domain.theme import (
    ThemeOverrides,
    fonts_are_whitelisted,
    get_theme,
    merge_theme,
    resolve_theme,
)

def test_merge_theme_palette_and_accent_series() -> None:
    base = get_theme("ivory")
    merged = merge_theme(
        base,
        ThemeOverrides.model_validate({"palette": {"accent": "#0D9488"}}),
    )
    assert merged.palette.accent == "#0D9488"
    assert merged.palette.chart_series[0] == "#0D9488"
    assert merged.palette.background == base.palette.background

def test_merge_theme_text_size() -> None:
    base = get_theme("ivory")
    merged = merge_theme(
        base,
        ThemeOverrides.model_validate({"text_styles": {"title": {"size_pt": 40}}}),
    )
    assert merged.text_styles["title"].size_pt == 40
    assert merged.text_styles["body"].size_pt == base.text_styles["body"].size_pt

def test_fonts_whitelist() -> None:
    assert fonts_are_whitelisted(get_theme("midnight").fonts)
    foreign = get_theme("ivory").fonts.model_copy(deep=True)
    foreign.display.pptx_latin = "Comic Sans MS"
    assert not fonts_are_whitelisted(foreign)

def test_resolve_theme_empty_overrides() -> None:
    assert resolve_theme("scholar", {}).id == "scholar"
    assert resolve_theme("scholar", None).palette.accent == get_theme("scholar").palette.accent
