# Metagame Decks

Top metagame decklists from [MTGTop8](https://www.mtgtop8.com), refreshed weekly and committed
straight into this repo as plain-text `.txt` files in the standard **MTGO/MTGA** format
(`<qty> <card name>` per line, a blank line before the sideboard).

Consumed by [xmage-gateway](https://github.com/t-my/xmage-gateway)'s "Select deck" picker, which
reads [`manifest.json`](manifest.json) and the deck files directly over `raw.githubusercontent.com`.

## Layout

```
manifest.json          # index: formats -> [{ file, name }]
decks/
├── legacy/
│   ├── 01-ur-tempo.txt
│   ├── 02-reanimator.txt
│   └── ...
├── modern/
│   └── ...
└── ...
```

- **Constructed** formats: one deck per archetype, numbered by metagame share (top 25 per format).
- **Commander** formats: recent competitive decks, deduped by commander. The commander (and partner,
  if any) is placed alone in the sideboard section, so the file is a valid commander list — the
  consumer reads the sideboard to know who the commander is.

## Formats

Standard, Modern, Pioneer, Pauper, Legacy, Vintage, Premodern,
Duel Commander, and Commander (mapped to MTGTop8's cEDH).

MTGTop8 doesn't track Timeless, Penny Dreadful, or Brawl, so those aren't published.

The weekly job refreshes a default constructed set (Standard, Pioneer, Modern, Legacy, Vintage,
Pauper, Premodern); run others on demand via the workflow's `formats` input or locally.

## Building locally

Requires [Babashka](https://github.com/babashka/babashka).

```bash
bb download.clj legacy      # refresh one format
bb download.clj all         # refresh every format
bb download.clj manifest    # rebuild manifest.json from what's on disk
```
