# Metagame Decks

Downloads top metagame decklists from [MTG Goldfish](https://www.mtggoldfish.com) across all major formats.

## Formats

Standard, Modern, Pioneer, Historic, Explorer, Timeless, Alchemy, Pauper, Legacy, Vintage, Penny Dreadful, Premodern, Duel Commander, Commander, Brawl

## Requirements

- [Babashka](https://github.com/babashka/babashka)

## Usage

```bash
# Download a single format (top 30 decks)
bb download.clj legacy
decks/
├── legacy/
│   ├── 01-dimir-tempo.txt
│   ├── 02-oops-all-spells.txt
│   └── ...
├── modern/
│   ├── 01-boros-energy.txt
│   └── ...
└── ...
```

## Weekly Release

A GitHub Actions workflow runs every Monday and publishes a release with all decks as a zip file.
