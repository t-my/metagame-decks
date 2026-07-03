# Metagame Decks

Top metagame decklists from [MTG Goldfish](https://www.mtggoldfish.com), refreshed weekly and
committed straight into this repo as plain-text `.txt` files in the standard **MTGO/MTGA** format
(`<qty> <card name>` per line, a blank line before the sideboard).

Consumed by [xmage-gateway](https://github.com/t-my/xmage-gateway)'s "Select deck" picker, which
reads [`manifest.json`](manifest.json) and the deck files directly over `raw.githubusercontent.com`.

## Layout

```
manifest.json          # index: formats -> [{ file, name }]
decks/
├── legacy/
│   ├── 01-dimir-tempo.txt
│   ├── 02-oops-all-spells.txt
│   └── ...
├── modern/
│   └── ...
└── ...
```

Each deck is numbered by metagame popularity (top 25 per format).

## Formats

Standard, Modern, Pioneer, Historic, Explorer, Timeless, Alchemy, Pauper, Legacy, Vintage,
Penny Dreadful, Premodern, Duel Commander, Commander, Brawl.

The weekly job refreshes a default constructed set (Standard, Pioneer, Modern, Legacy, Vintage,
Pauper, Premodern); run others on demand via the workflow's `formats` input or locally.

## Building locally

Requires [Babashka](https://github.com/babashka/babashka).

```bash
bb download.clj legacy      # refresh one format
bb download.clj all         # refresh every format
bb download.clj manifest    # rebuild manifest.json from what's on disk
```
