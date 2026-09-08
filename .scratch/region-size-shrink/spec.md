# Region size and shrink

Residents and admins can inspect a region's inclusive X/Z dimensions with `/rg size`.
`/rg extend` without a distance shows the same summary. `/rg shrink <distance>`
pulls the faced edge inward while preserving a positive span, minimum area, parent
containment, and all nested sub-regions.
