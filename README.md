# XMage Metagame Decks

[![Download latest](https://img.shields.io/github/v/release/t-my/metagame-decks?label=download&sort=date)](https://github.com/t-my/metagame-decks/releases/latest/download/metagame-decks.zip)

Top metagame decklists from [MTG Goldfish](https://www.mtggoldfish.com) in [XMage](https://github.com/magefree/mage) `.dck` format. Updated weekly.

## Formats

Standard, Modern, Pioneer, Historic, Explorer, Timeless, Alchemy, Pauper, Legacy, Vintage, Penny Dreadful, Premodern, Duel Commander, Commander, Brawl

## Usage

Download the latest zip from the badge above, extract, and import the `.dck` files into XMage.

```
decks/
├── legacy/
│   ├── 01-dimir-tempo.dck
│   ├── 02-oops-all-spells.dck
│   └── ...
├── modern/
│   ├── 01-boros-energy.dck
│   └── ...
└── ...
```

Each deck is numbered by metagame popularity (top 30 per format). Card set codes and collector numbers are resolved via [Scryfall](https://scryfall.com).

## Building locally

Requires [Babashka](https://github.com/babashka/babashka).

```bash
bb download.clj legacy
```
