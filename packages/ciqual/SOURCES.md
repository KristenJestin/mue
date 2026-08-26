# Where the catalogue comes from

`apps/android/app/src/main/assets/ciqual/catalogue-ciqual-2025.1.json` is generated, not
written. This file records what it is generated from, how to obtain that, and what it must
hash to.

## The source

| | |
|---|---|
| Release | **Ciqual 2025** — Table de composition nutritionnelle des aliments |
| Publisher | ANSES, Agence nationale de sécurité sanitaire de l'alimentation, de l'environnement et du travail |
| Published | 19 November 2025 |
| Contents | 3 484 foods, 74 constituents |
| Licence | Licence Ouverte / Open Licence 2.0 (Etalab) |
| Landing page | <https://ciqual.anses.fr/cms/en/2025-anses-ciqual-table> |
| Data | DOI [10.57745/RDMHWY](https://doi.org/10.57745/RDMHWY) on Recherche Data Gouv |

**There is no single archive for the 2025 release.** The Ciqual site links only the
methodological PDF; the tables themselves moved to the Recherche Data Gouv repository and
are published as eight individual files. The repository can zip them on request, but that
zip embeds a generated manifest and timestamps and is not byte-stable, so its hash would
differ on every download and gate nothing.

`ciqual.source.json` therefore records a SHA-256 per file, and folds those into one
`archiveSha256`:

```
sha256( concat( "<sha256>  <fileName>\n" for each file, ordered by fileName ) )
```

which is reproducible by hand:

```sh
cd packages/ciqual/.source
for f in $(ls | sort); do echo "$(sha256sum "$f" | cut -d' ' -f1)  $f"; done | sha256sum
# d6a88475449526c70284e1f5057c9d1b08e4d9826ff2e5a0c70c90752c72c912
```

The per-file MD5s in `ciqual.source.json` are the publisher's own, copied from the
repository's file listing, so the two hash families cross-check each other: the SHA-256s
were computed locally from bytes whose MD5 the publisher had already stated.

## Getting the files

The source is ~71 MB uncompressed and is **not committed**. `packages/ciqual/.source/` is
gitignored. Download into it:

| File | Bytes | Used by the build |
|---|---:|---|
| `alim_2025_11_03.xml` | 1 581 031 | yes — food names and groups |
| `alim_grp_2025_11_03.xml` | 80 421 | yes — group and sub-group labels |
| `compo_2025_11_03.xml` | 69 243 149 | yes — composition |
| `const_2025_11_03.xml` | 17 955 | no — read once to fix the constituent codes |
| `sources_2025_11_03.xml` | 871 783 | no — provenance of each measurement |

```sh
mkdir -p packages/ciqual/.source && cd packages/ciqual/.source
curl -Lo alim_2025_11_03.xml     "https://entrepot.recherche.data.gouv.fr/api/access/datafile/666252"
curl -Lo alim_grp_2025_11_03.xml "https://entrepot.recherche.data.gouv.fr/api/access/datafile/666250"
curl -Lo compo_2025_11_03.xml    "https://entrepot.recherche.data.gouv.fr/api/access/datafile/666249"
curl -Lo const_2025_11_03.xml    "https://entrepot.recherche.data.gouv.fr/api/access/datafile/666246"
curl -Lo sources_2025_11_03.xml  "https://entrepot.recherche.data.gouv.fr/api/access/datafile/666248"
```

Or set `CIQUAL_SOURCE_DIR` to wherever they already live.

Then:

```sh
bun run --filter @mue/ciqual source:verify     # hashes only
bun run --filter @mue/ciqual catalogue:report  # full pipeline, writes nothing
bun run --filter @mue/ciqual catalogue:build   # regenerates the committed asset
```

`catalogue:build` calls the verification first and **refuses to run on a mismatch**. That
is not ceremony. Ciqual reuses `alim_code` across releases and changes the values under
them, so a 2020 file dropped into `.source` parses perfectly and produces a catalogue that
is wrong food by food, with nothing on screen to show for it.

## Which constituents are read

Six of the seventy-four, from `const_2025_11_03.xml`:

| `const_code` | Ciqual name | Shipped as |
|---|---|---|
| `328` | Energy, Regulation EU No 1169/2011 (kcal/100 g) | `energyMilliKcal` |
| `25000` | Protein (g/100 g) | `proteinMilligrams` |
| `31000` | Carbohydrate (g/100 g) | `carbsMilligrams` |
| `40000` | Fat (g/100 g) | `fatMilligrams` |
| `34100` | Fibres (g/100 g) | `fibreMilligrams` |
| `400` | Water (g/100 g) | **nothing** |

Water is read only to derive PRD_FOOD 8.6's cooked ratio and is deliberately not shipped:
PRD_FOOD 9.1 keeps five constituents.

Energy is `328` — the EU 1169/2011 figure — and not `333`, the N × Jones factor variant.
Protein is `25000`, the declared value, not `25003`'s N × 6.25 estimate.

## How a `teneur` cell is read

Ciqual writes four different things in that column and three of them are not numbers.
Across the six constituents above, in this release:

| Cell | Count | Read as |
|---|---:|---|
| a number, e.g. `59,7` | 19 518 | the value, with the comma as the decimal point |
| `-` | 877 | **null** — not determined |
| `traces` | 201 | **null** — present, below quantification |
| `< x`, e.g. `&lt; 0,01` | 308 | **null** — present, below a stated limit |

An explicit `0` is among the 19 518 and means a measured zero. PRD_FOOD 13.1 forbids
reading any of the other three as zero, and the 509 trace-class cells are counted in the
build report rather than quietly rounded down.

## Licence and attribution

The Ciqual table is published under the Etalab Open Licence 2.0, which permits reuse
including commercially, and requires attribution of the source and of the date of the last
update. The generated asset carries `"version": "ciqual-2025.1"` on the catalogue and
`sourceVersion` on every food it seeds; the human-readable attribution belongs in
`Profile → About` beside PRD_FOOD 9.2's Open Food Facts notice:

> Generic food data: Table de composition nutritionnelle des aliments Ciqual 2025, ANSES.
> Licence Ouverte / Open Licence 2.0.

## Regenerating for a later release

1. Download the new files into `.source/`.
2. Update `ciqual.source.json`: release name, version, date, file names, sizes, download
   URLs, per-file SHA-256, `archiveSha256`.
3. Run `bun run --filter @mue/ciqual catalogue:report` and read it. The counts are the
   review: rows per group, ratios derived, trace cells dropped, names shortened, portions
   attached.
4. Check `portions.json` — `alim_code` is stable across releases but foods are retired,
   and the report prints any portion written for a food the new subset does not ship.
5. Run `bun run --filter @mue/ciqual catalogue:build`, commit the new
   `catalogue-<version>.json` and `catalogue.sha256`. PRD_FOOD 20.2 requires that seeding
   an updated catalogue never touches a custom food or a journal line, so the old file may
   be deleted only once nothing is still seeding from it.
