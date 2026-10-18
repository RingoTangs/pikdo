import json
from decimal import Decimal
from pathlib import Path

import pytest

ITEM_ID = "6ab10b35d528ea0015dd1b7e"


@pytest.fixture
def payload():
    return json.loads(
        (Path(__file__).parents[1] / "examples/response.json").read_text(), parse_float=Decimal
    )
