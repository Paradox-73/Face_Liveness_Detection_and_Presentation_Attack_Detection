#!/usr/bin/env python3
"""Download the MOSIP reference data MockSync serves and convert it to JSON snapshots.

Sources (pinned by commit, licence MPL-2.0, see data/LICENSE-NOTE.md):
  * mosip/mosip-data   branch 1.2.0.1, mosip_master/xlsx/*.xlsx  -> data/upstream/mosip-data/*.json
  * mosip/mosip-config tag v1.2.0.1, registration-default.properties,
                       application-default.properties, applicanttype.mvel -> data/upstream/mosip-config/
  * the desktop client's own initial.sql GLOBAL_PARAM seed rows       -> data/client-seed/initial-global-params.properties

The JSON snapshots are committed, so MockSync itself needs no network and no Python.
Run this script only to refresh them:  python scripts/fetch-data.py
Requires Python 3.8+ and openpyxl (pip install openpyxl).
"""
import hashlib
import json
import os
import re
import sys
import urllib.request

import openpyxl

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, "data")
REPO_ROOT = os.path.dirname(os.path.dirname(ROOT))

MOSIP_DATA = {
    "repo": "https://github.com/mosip/mosip-data",
    "ref": "1.2.0.1",
    "commit": "6985016c009ec0334b5e036f45e1c2847d1f5f0b",
    "path": "mosip_master/xlsx",
    "licence": "MPL-2.0",
}
MOSIP_CONFIG = {
    "repo": "https://github.com/mosip/mosip-config",
    "ref": "v1.2.0.1",
    "commit": "d4c4f7edf4ef0dc9218a5e036ebe3e20ae99d04f",
    "licence": "MPL-2.0",
}
CLIENT_INITIAL_SQL = os.path.join(
    REPO_ROOT, "clients", "desktop", "registration", "registration-services", "src", "main", "resources", "initial.sql")

# Tables the desktop client's ClientSettingSyncHelper stores, plus the schema and UI spec tables.
TABLES = [
    "app_authentication_method", "app_role_priority", "applicant_valid_document", "biometric_attribute",
    "biometric_type", "blocklisted_words", "doc_category", "doc_type", "dynamic_field", "identity_schema",
    "language", "loc_hierarchy_list", "location", "machine_spec", "machine_type", "process_list",
    "reason_category", "reason_list", "reg_center_type", "registration_center", "screen_authorization",
    "screen_detail", "sync_job_def", "template", "ui_spec",
]
# Template modules used by the registration clients (10002 "Registration Client", 10008 client notifications).
TEMPLATE_MODULES = {10002, 10008}
CONFIG_FILES = ["registration-default.properties", "application-default.properties", "applicanttype.mvel"]

TRUE_VALUES = {"true", "t", "=true()", "y", "yes"}
FALSE_VALUES = {"false", "f", "=false()", "n", "no"}


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def normalise(column, value):
    """Turn spreadsheet cell values into plain JSON values."""
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.upper() == "NULL" or stripped.lower() == "now()":
            return None
        if column.startswith("is_"):
            if stripped.lower() in TRUE_VALUES:
                return True
            if stripped.lower() in FALSE_VALUES:
                return False
        return value
    if isinstance(value, float) and value.is_integer() and column not in ("id_version", "version",
                                                                       "identity_schema_version"):
        return int(value)
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return value


def convert_xlsx(content, table):
    import io
    workbook = openpyxl.load_workbook(io.BytesIO(content), read_only=True)
    sheet = workbook.worksheets[0]
    rows = list(sheet.iter_rows(values_only=True))
    header = [h for h in rows[0] if h is not None]
    out = []
    for raw in rows[1:]:
        if raw is None or all(cell is None for cell in raw[:len(header)]):
            continue
        row = {column: normalise(column, raw[i] if i < len(raw) else None) for i, column in enumerate(header)}
        if table == "template" and row.get("module_id") not in TEMPLATE_MODULES:
            continue
        out.append(row)
    return {"table": table, "columns": header, "rows": out}


def extract_initial_params(sql_text):
    pattern = re.compile(r"INSERT INTO \"REG\"\.\"GLOBAL_PARAM\" VALUES \('([^']*)','[^']*','((?:[^']|'')*)'")
    lines = ["# GLOBAL_PARAM rows seeded by the desktop client's initial.sql (MPL-2.0, mosip/registration-client).",
             "# MockSync serves them with the lowest precedence so a config sync does not delete them."]
    for code, value in pattern.findall(sql_text):
        value = value.replace("''", "'").replace("\\", "\\\\")
        lines.append("%s=%s" % (code, value))
    return "\n".join(lines) + "\n"


def main():
    sources = {"mosip-data": dict(MOSIP_DATA, files={}), "mosip-config": dict(MOSIP_CONFIG, files={})}
    data_dir = os.path.join(DATA, "upstream", "mosip-data")
    config_dir = os.path.join(DATA, "upstream", "mosip-config")
    os.makedirs(data_dir, exist_ok=True)
    os.makedirs(config_dir, exist_ok=True)

    for table in TABLES:
        url = "https://raw.githubusercontent.com/mosip/mosip-data/%s/%s/%s.xlsx" % (
            MOSIP_DATA["commit"], MOSIP_DATA["path"], table)
        content = fetch(url)
        sources["mosip-data"]["files"][table + ".xlsx"] = hashlib.sha256(content).hexdigest()
        snapshot = convert_xlsx(content, table)
        snapshot["source"] = url
        with open(os.path.join(data_dir, table + ".json"), "w", encoding="utf-8", newline="\n") as handle:
            json.dump(snapshot, handle, ensure_ascii=False, indent=1, sort_keys=False)
            handle.write("\n")
        print("%-28s %5d rows" % (table, len(snapshot["rows"])))

    for name in CONFIG_FILES:
        url = "https://raw.githubusercontent.com/mosip/mosip-config/%s/%s" % (MOSIP_CONFIG["commit"], name)
        content = fetch(url)
        sources["mosip-config"]["files"][name] = hashlib.sha256(content).hexdigest()
        with open(os.path.join(config_dir, name), "wb") as handle:
            handle.write(content)
        print("%-28s %5d bytes" % (name, len(content)))

    if os.path.exists(CLIENT_INITIAL_SQL):
        seed_dir = os.path.join(DATA, "client-seed")
        os.makedirs(seed_dir, exist_ok=True)
        with open(CLIENT_INITIAL_SQL, encoding="utf-8") as handle:
            params = extract_initial_params(handle.read())
        with open(os.path.join(seed_dir, "initial-global-params.properties"), "w", encoding="utf-8",
                  newline="\n") as handle:
            handle.write(params)
        print("initial-global-params       %5d keys" % (params.count("\n") - 2))
    else:
        print("warning: %s not found, client seed params not refreshed" % CLIENT_INITIAL_SQL, file=sys.stderr)

    with open(os.path.join(DATA, "SOURCES.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(sources, handle, indent=2)
        handle.write("\n")


if __name__ == "__main__":
    main()
