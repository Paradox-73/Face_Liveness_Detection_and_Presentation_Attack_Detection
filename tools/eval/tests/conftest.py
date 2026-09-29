"""Puts tools/eval on the import path so the tests can import the scripts as modules."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
