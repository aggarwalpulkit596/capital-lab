# Public data provenance

The dashboard includes **305 observed days** derived from Daqing Chen's [Online Retail dataset at UCI](https://archive.ics.uci.edu/dataset/352/online+retail). The original workbook contains 541,909 transaction lines from a UK online retailer, December 2010–December 2011. Source currency is GBP.

Citation: Chen, D. (2015). *Online Retail* [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C5BW33.

The source and bundled derived aggregates use [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), separately from the project's Apache 2.0 code license. See [NOTICE](../NOTICE).

## Reproduce

Python 3 is sufficient; no third-party Python package is needed:

```sh
python3 scripts/import_uci.py --download
```

The importer downloads the official archive into ignored `data/raw/`, then rebuilds [uci-retail-daily.json](../src/main/resources/data/uci-retail-daily.json). Without `--download`, it reuses the local archive. The approximately 23 MB original workbook is not bundled with the repository. The derived file is bundled, so running the dashboard does not require internet access after build dependencies are available.

## Transformations

1. Aggregate all countries by the workbook's invoice date. No customer identifiers, descriptions, or individual transaction rows are retained in the derived file.
2. Exclude nonpositive unit prices and zero quantities (2,517 source rows). Include otherwise usable rows without a customer ID. Retain valid charges and adjustments rather than pretending every line is a subscription.
3. Treat an invoice starting with `C`, or a negative quantity, as a cancellation. That is **not** a confirmed chargeback or fraud label.
4. Multiply quantity by price with decimal arithmetic; round each extended line amount half-up to GBP pence. Aggregate gross and cancellation amounts separately, then calculate net.
5. Keep the 305 observed invoice dates; do not silently fill missing days with zero sales. The illustrative velocity baseline uses only the preceding seven observed days, never future observations.
6. For the payment replay only, convert amounts at **1.25 simulated USD per GBP**, half-up to cents. This chosen constant is not historical FX. Map the selected day into a fresh synthetic reporting period so a historical dataset is not incorrectly described as current earnings.

Hashes of the retrieved files:

```text
Archive SHA-256: f5385cbb54bbebf7196389109c6b0621faab0c304e3702548165e71c84aede8b
Workbook SHA-256: 43465a06f2ccf7c8b5bd2892bc7defb52f97487934fe93b16ae4c3936424676d
```

The importer records new hashes if UCI changes the archive; review such a change before replacing the bundled derivative. Dashboard export includes source attribution and the selected day's values. No data is sent to an external service during replay.

## What this supports

Reproducible ingestion, revenue changes, cancellation ratios, transparent review signals, and simulation with real-shaped amounts. It cannot validate subscription churn or renewal models, app-store payout timing, borrower defaults, or fraud detection accuracy. The bank, receivables, risk decisions, and payouts remain simulated.
