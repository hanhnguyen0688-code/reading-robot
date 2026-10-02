"""Runs the Node parity test so `pytest` also guards the JavaScript (on-device) engine."""
import shutil, subprocess
from pathlib import Path
import pytest

ROOT = Path(__file__).resolve().parent.parent


@pytest.mark.skipif(shutil.which("node") is None, reason="node not installed")
def test_js_engine_matches_python():
    subprocess.run(["python3", str(ROOT / "tests" / "make_parity_fixtures.py")], check=True, cwd=ROOT)
    out = subprocess.run(["node", str(ROOT / "tests" / "engine_parity.test.js")], capture_output=True, text=True, cwd=ROOT)
    assert out.returncode == 0, out.stderr or out.stdout
    assert "identical to Python" in out.stdout
