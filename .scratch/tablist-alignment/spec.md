# Tab list: align hearts to a column

**Reverted by request** ("forget the alignment, put the ping next to the
name with only one space in between") once real vanilla hearts
(`.scratch/tablist-real-hearts/`) made the padding this describes far less
load-bearing than it was against the old hand-drawn glyph bar — vanilla's
own `list`-slot column is already aligned on its own. `tabDisplayName` is
back to the plain `<name> [<N>ms]`, one space, no padding. Left the rest of
this note for the record of what was tried and why.

A tab entry's display name **was** `<name><padding> [<N>ms] <hearts>`.
`padding` right-padded the name to the length of the longest name among
everyone currently online, so the `[Nms]` + hearts block started in the
same place on every row instead of drifting with name length.

## Ping icon

Asked: replace vanilla's own connection-strength icon (the bars drawn at the
far right of every tab-list row) with the millisecond number as text.

Not possible from the server side: that icon is rendered client-side by
vanilla's own tab-list code from the latency field on the packet — it is not
part of the display-name text this mod controls, and nothing server-side can
suppress or replace it. The mod already showed `[Nms]` as text before this
change (`TabListFeature.tabDisplayName`); what changed here is only where
that text sits — immediately left of the hearts, both now padded into a
column — matching the "else stick to the number left of hearts" fallback the
request named.

## Alignment caveat

Padding is character-count, not pixel-width: vanilla's default font is not
monospaced, so a row of narrow letters (`iiii`) and a row of wide ones
(`WWWW`) of equal character length will not land pixel-identical. True
per-glyph alignment would need known glyph widths for the font, which this
mod does not model. Character padding is the correction available from
plain text and reads as aligned for ordinary names.

## Where

`TabListFeature.namePadding(player)` — recomputed on every display-name
build (called once per entry from `ServerPlayerMixin`, and again by the
periodic tab-list refresh every 20 ticks), so the whole list stays aligned
to whoever is online each time it looks.
