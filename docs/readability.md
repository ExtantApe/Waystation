# Readability and colour

A review of what players see, for colour-blind players and for contrast, done 2026-11-08 for 0.3.1. The owner
asked for it the same day (see `decisions.md`). The colour checks below are arithmetic, held in place by
`ReadabilityTest`. Nobody has looked at any of it in a real game client yet.

## Is colour ever the only clue?

No, after this review. Each state has a word, and gates also differ in shape or material:

| What | Colour | Also |
| --- | --- | --- |
| Open gate | coloured glass in the opening | Labelled with where it goes |
| Locked gate (for sale) | yellow text | "Locked: <price>" and "/buygate <world>" |
| Closed line (world deleted) | grey | Cracked frame, nothing inside, no light, "(line closed)" |
| Fargate | yellow, black and red | Striped frame, its own shape, "Leaving this server" |
| Platforms | glass colour per platform | "Platform 1", "Platform 2" and so on, with the group's name |
| Chat messages | red, yellow, green | Every one says what happened in words: "Cancelled: you moved", "Nothing was taken" |

The stripes of a fargate are yellow on black, which differ in brightness as well as hue, so they read for every
common type of colour blindness (red-green and blue-yellow) and in greyscale.

## Contrast

Floating labels float over whatever is behind them. Minecraft puts a faint plate behind them, about 25% black. Over
snow or a bright sky that's too faint for most of the colours we use. WCAG contrast, label colour against the plate
in front of something white:

| Colour | Minecraft's plate (25%) | Waystation's plate (75%) | 75%, in front of black |
| --- | --- | --- | --- |
| white | 1.8 | 10.4 | 21.0 |
| yellow | 1.7 | 9.7 | 19.7 |
| aqua | 1.5 | 8.5 | 17.1 |
| gold | 1.0 | 5.4 | 11.0 |
| gray | 1.3 | 4.5 | 9.0 |
| light purple | 1.4 | 3.9 | 8.0 |
| red | 1.7 | 3.3 | 6.7 |
| dark gray | 4.1 | 1.4 | 2.8 |

Labels are large text (scale 0.8 and up, read from a few blocks away), so the bar is WCAG's 3:1 for large text.
With the 75% plate every shipped label colour clears it in front of both white and black. Dark grey can't clear it on
any plate in front of black, so it's gone from the shipped labels.

## What changed in 0.3.1

- Every floating label gets a text shadow and a 75% black plate. `label-backing` in `config.yml` sets the
  plate, from 0 (Minecraft's own) to 100. Older configs without it get 75.
- The closed-line label was dark grey on grey. It's now the world's name in white, then "(line closed)" in grey.
- The return countdown on the action bar was grey. The action bar has no plate, only a shadow, so it's white now.
- The README described fargates as obsidian. They're a yellow and black striped frame, and it says so now.

Old configs keep their own label text, since Waystation never rewrites a config. To pick up the new closed-line label,
set `station.dead-label` to `"<white><name></white> <gray>(line closed)"`. For the countdown, the `return-countdown`
and `return-checking` messages.

## Tested

- `ReadabilityTest`: every shipped label's colours against the plate in front of white and in front of black; that
  closed, locked and leaving labels say so in words; that the countdown is white. Positive controls: dark grey and
  Minecraft's 25% plate both fail the same check, and putting the old dark grey label back makes the test fail.
- `station` bot suite, on a throwaway Paper 26.1.2 server: all 29 labels in the station have the shadow and the
  plate, `label-backing: 0` takes the plate away and keeps the shadow, and no label is redrawn between passes.
  51/51. The `acceptance` suite passed 177/177 with the same plugin code. Not rerun on 26.2 or 26.3 for this change.

## Not tested

- How any of this looks in a real game client.
- Owners' own label colours, set in `config.yml`. The plate helps them too, but nothing checks them.
