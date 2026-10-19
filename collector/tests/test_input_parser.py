"""Offline input-parser coverage migrated from the removed network resolver."""

import re
from pathlib import Path

import pytest

from xhs.errors import InputError
from xhs.input_parser import parse_input, preprocess_input, validate_url

ITEM_ID = "6ab10b35d528ea0015dd1b7e"
OTHER_ID = "6ab10b35d528ea0015dd1b7d"
H5 = f"https://www.xiaohongshu.com/goods-detail/{ITEM_ID}"
SHORT = "https://xhslink.com/m/9izjN4oPZd4"
SHARE = f"【小红书】【前3000单必出盘】 😆 3ukhXCGSwNE 😆 {SHORT} 点击链接或者复制本条信息打开【小红书app】查看 CZ6125"


@pytest.mark.parametrize(
    "value,source",
    [
        (ITEM_ID, "direct_id"),
        (ITEM_ID.upper(), "direct_id"),
        (f"  {ITEM_ID}\n", "direct_id"),
        (H5, "h5_url"),
        (H5.replace("www.", ""), "h5_url"),
        (H5 + "/", "h5_url"),
        (H5 + "?xsec_token=SENTINEL_TOKEN", "h5_url"),
        (H5 + "#section", "h5_url"),
        (H5.replace(ITEM_ID, ITEM_ID.upper()), "h5_url"),
        (H5.replace("https://www.", "HTTPS://WWW."), "h5_url"),
        (H5.replace(".com/", ".com:443/"), "h5_url"),
        (f"[商品]({H5})", "share_text"),
        (f"<{H5}>", "share_text"),
        (f"({H5})", "share_text"),
        (f"查看：{H5}。", "share_text"),
        (f"😆 商品\n  {H5}\n 点击查看", "share_text"),
    ],
)
def test_local_inputs_do_not_access_network(value: str, source: str) -> None:
    parsed = parse_input(value)
    assert parsed.source_type == source
    assert parsed.item_id == ITEM_ID and parsed.short_target is None


@pytest.mark.parametrize(
    "value,source",
    [
        (SHORT, "short_url"),
        (SHORT + " ", "short_url"),
        (SHARE, "share_text"),
        (SHARE.replace("https://", r"https\://") + "&#x20;", "share_text"),
        (SHARE.replace("https://", r"https:\/\/").replace("/m/", r"\/m\/"), "share_text"),
        (SHARE.replace(" 点击", "\n\n   点击") + "   ", "share_text"),
        (f"[分享商品]({SHORT})", "share_text"),
        (f"<{SHORT}>", "share_text"),
        (f"（{SHORT}）", "share_text"),
        (f"分享：{SHORT}，点击查看。", "share_text"),
        (SHARE.replace(SHORT, SHORT + "?a=1&amp;b=2"), "share_text"),
        (SHORT.replace("xhslink.com", "www.xhslink.com"), "short_url"),
    ],
)
def test_short_and_share_variants(value: str, source: str) -> None:
    parsed = parse_input(value)
    assert parsed.source_type == source and parsed.item_id is None
    assert parsed.short_target.path == "/m/9izjN4oPZd4"
    assert parsed.short_target.hostname in {"xhslink.com", "www.xhslink.com"}


@pytest.mark.parametrize(
    "value",
    [
        "a" * 23,
        "a" * 25,
        "g" * 24,
        "",
        "   ",
        "x" * 8193,
        f"http://www.xiaohongshu.com/goods-detail/{ITEM_ID}",
        f"https://www.xiaohongshu.com.evil.test/goods-detail/{ITEM_ID}",
        f"https://xiaohongshu.com@evil.test/goods-detail/{ITEM_ID}",
        f"https://user:pass@www.xiaohongshu.com/goods-detail/{ITEM_ID}",
        H5.replace(".com/", ".com:8000/"),
        H5.replace(".com/", ".com:invalid/"),
        H5.replace(".com/", ".com./"),
        H5.replace("www.xiaohongshu.com", "127.0.0.1"),
        "https://localhost/m/a",
        "https://[::1]/m/a",
        "https://10.0.0.1/m/a",
        "https://169.254.169.254/m/a",
        "https://2130706433/m/a",
        "https://xhslink.com.evil.test/m/a",
        "https://evil.test@xhslink.com/m/a",
        "https://xhslink.com:80/m/a",
        "https://xhslink.com:444/m/a",
        "https://xhslink%2ecom/m/a",
        "https://xhslink.com\\@evil.test/m/a",
        r"https\://evil.test/m/a",
        "https://xhslink.com\x00/m/a",
    ],
)
def test_invalid_inputs_fail_before_network(value: str) -> None:
    with pytest.raises(InputError) as error:
        parse_input(value)
    assert error.value.code == "INVALID_PRODUCT_INPUT"


@pytest.mark.parametrize(
    "value",
    [
        f"https://www.xiaohongshu.com/explore/{ITEM_ID}",
        H5 + "/extra",
        H5.replace(ITEM_ID, "g" * 24),
        f"https://www.xiaohongshu.com/?item_id={ITEM_ID}",
        "https://xhslink.com/",
        "https://xhslink.com/a/b/c",
        "https://xhslink.com/m/%2e%2e",
    ],
)
def test_non_product_links(value: str) -> None:
    with pytest.raises(InputError) as error:
        parse_input(value)
    assert error.value.code == "NOT_PRODUCT_LINK"


@pytest.mark.parametrize("value", ["没有链接 😆 CZ6125", f"追踪代码 {ITEM_ID}", "商品分享&#x20;"])
def test_missing_link_does_not_search_for_id(value: str) -> None:
    with pytest.raises(InputError) as error:
        parse_input(value)
    assert error.value.code == "PRODUCT_LINK_NOT_FOUND"


@pytest.mark.parametrize(
    "value",
    [
        f"{H5} {H5.replace(ITEM_ID, OTHER_ID)}",
        f"{SHORT} https://xhslink.com/m/different",
        f"{H5} {SHORT}",
    ],
)
def test_ambiguous_links_fail_before_network(value: str) -> None:
    with pytest.raises(InputError) as error:
        parse_input(value)
    assert error.value.code == "AMBIGUOUS_PRODUCT_INPUT"


def test_repeated_equivalent_h5_links_are_not_conflicts() -> None:
    value = f"{H5}?xsec_token=one {H5.replace('www.', '')}/#other"
    parsed = parse_input(value)
    assert parsed.item_id == ITEM_ID and parsed.source_type == "share_text"


def test_h5_nested_urls_are_not_rescanned() -> None:
    parsed = parse_input(H5 + f"?nested=https://www.xiaohongshu.com/goods-detail/{OTHER_ID}")
    assert parsed.item_id == ITEM_ID and parsed.source_type == "h5_url"


@pytest.mark.parametrize(
    "location",
    [
        "https://evil.test/m/a",
        "http://xhslink.com/m/a",
        "//evil.test/m/a",
        "https://127.0.0.1/m/a",
        "https://10.0.0.1/m/a",
        "https://[::1]/m/a",
        "https://localhost/m/a",
        "https://169.254.169.254/m/a",
        "https://user:pass@xhslink.com/m/a",
        "https://xhslink.com:8080/m/a",
        "https://xhslink.com.evil.test/m/a",
        "https://xhslink.com\\@evil.test/m/a",
        "javascript:alert(1)",
        "https://xhslink.com\t.evil.test/m/a",
    ],
)
def test_untrusted_redirect_is_never_requested(location: str) -> None:
    with pytest.raises(InputError) as error:
        validate_url(location, redirect=True)
    assert error.value.code == "UNTRUSTED_REDIRECT_TARGET"


def test_preprocessing_is_finite_and_does_not_trust_unknown_domains() -> None:
    assert preprocess_input(r"https\://evil.test/m/a&#x20;") == "https://evil.test/m/a"
    assert preprocess_input("&amp;amp;") == "&amp;"
    with pytest.raises(InputError):
        parse_input("https://evil.test/m/a")
    with pytest.raises(InputError):
        parse_input("https%3A%2F%2Fxhslink.com%2Fm%2Fa")
    with pytest.raises(InputError):
        validate_url("https://xhslink.com\n.evil.test/m/a")


@pytest.mark.parametrize(
    "location", ["https:///m/next", "https:m/next", "///m/next", "https://[invalid/m/a"]
)
def test_malformed_absolute_location_is_not_joined_into_trusted_host(location: str) -> None:
    with pytest.raises(InputError):
        validate_url(location, redirect=True)


def test_original_share_text_examples_parse_without_network():
    source = (Path(__file__).parents[1] / "examples/share_text.md").read_text()
    entries = re.findall(r"```text\n(.*?)```", source, re.S)
    assert entries
    for entry in entries:
        assert parse_input(entry).source_type in {"share_text", "short_url", "h5_url", "direct_id"}
