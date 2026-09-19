#!/usr/bin/env python3
"""Rebuild daily, de-identified GBP aggregates from UCI Online Retail. Python stdlib only."""
import argparse
from collections import defaultdict
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://archive.ics.uci.edu/static/public/352/online%2Bretail.zip'
NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'

def build(archive):
    with zipfile.ZipFile(archive) as outer:
        workbook = outer.read('Online Retail.xlsx')
    daily = defaultdict(lambda: dict(grossMinor=0, cancellationsMinor=0, saleLines=0, cancellationLines=0))
    source_rows = excluded = 0
    with zipfile.ZipFile(io.BytesIO(workbook)) as book:
        strings = [''.join(node.itertext()) for node in ET.fromstring(book.read('xl/sharedStrings.xml'))]
        with book.open('xl/worksheets/sheet1.xml') as sheet:
            for _, row in ET.iterparse(sheet, events=('end',)):
                if row.tag != NS + 'row':
                    continue
                if row.attrib.get('r') == '1':
                    row.clear()
                    continue
                source_rows += 1
                cells = {}
                for cell in row:
                    value = cell.find(NS + 'v')
                    if value is not None:
                        text = value.text
                        cells[''.join(c for c in cell.attrib['r'] if c.isalpha())] = strings[int(text)] if cell.attrib.get('t') == 's' else text
                try:
                    quantity, price = Decimal(cells['D']), Decimal(cells['F'])
                    if price <= 0 or quantity == 0:
                        excluded += 1
                        continue
                    date = (datetime(1899, 12, 30) + timedelta(days=float(cells['E']))).date().isoformat()
                    # Round the extended line total, never binary floating-point money.
                    minor = int((abs(quantity) * price * 100).quantize(Decimal('1'), rounding=ROUND_HALF_UP))
                    cancellation = cells['A'].upper().startswith('C') or quantity < 0
                    item = daily[date]
                    item['cancellationsMinor' if cancellation else 'grossMinor'] += minor
                    item['cancellationLines' if cancellation else 'saleLines'] += 1
                except (KeyError, ValueError, ArithmeticError):
                    excluded += 1
                finally:
                    row.clear()
    days = [dict(date=date, **values, netMinor=values['grossMinor']-values['cancellationsMinor']) for date, values in sorted(daily.items())]
    return dict(
        title='UCI Online Retail — daily aggregates',
        creator='Daqing Chen', citation='Chen, D. (2015). Online Retail [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C5BW33',
        sourceUrl='https://archive.ics.uci.edu/dataset/352/online+retail', downloadUrl=URL,
        license='CC BY 4.0', licenseUrl='https://creativecommons.org/licenses/by/4.0/',
        sourceCurrency='GBP', sourceRows=source_rows, excludedRows=excluded,
        archiveSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
        workbookSha256=hashlib.sha256(workbook).hexdigest(),
        transformations=['All countries aggregated by invoice date; customer IDs and descriptions omitted.',
                         'Exclude nonpositive prices and zero quantities; include rows without customer IDs.',
                         'Cancellation = invoice starts C or quantity is negative; not a fraud label.',
                         'Extended line amounts rounded half-up to GBP pence; missing calendar days are not filled.',
                         'Dashboard USD replay uses an illustrative fixed 1.25 USD/GBP, not historical FX.'],
        days=days)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    archive = ROOT / 'data/raw/online-retail.zip'
    if args.download:
        archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=90) as response:
            archive.write_bytes(response.read())
    result = build(archive)
    output = ROOT / 'src/main/resources/data/uci-retail-daily.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(f"{result['sourceRows']} source rows, {result['excludedRows']} excluded, {len(result['days'])} days → {output}")
