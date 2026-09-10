# JosmMCP tool notes

Per-tool behaviour that is easy to get wrong. Groups: read, view, tags, geometry,
delete, history, files, download — any of them can be switched off in the preferences,
independently of global read-only mode.

## search_elements

- `key=value` is an **exact, case sensitive match with no wildcard**. `source:date=2014`
  matches nothing on a value of `2014-03-24`. Use `regex=true` for that; the expression
  must match the **whole** value, and `case_sensitive` then decides case folding.
- **Round brackets cannot be used in a regex** — JOSM's query tokenizer splits on them.
  Write character classes instead of alternations.
- `fields`, `max_results` (default 50), `offset`, `output_path` control the size.
- `include_geometry` adds `nodes: [{id, lat, lon}]` to every way; it survives a `fields`
  filter. Relations are **not** expanded — use `read_relation` with `include_geometry`.
  Costs ~40 bytes per node, so pair it with `output_path`.
- Spatial filters: `bbox`, `polygon`, `center` + `radius_m`. `ids` (up to 5000) makes
  the query optional, so id-set ∩ area is one call.
- Runs off the event dispatch thread under the dataset read lock; most other tools run
  on the EDT.

## validate

- Scopes: `changes` (default; **includes parent ways of moved nodes**), `selection`,
  `all`, `bbox`, `elements` (nodes bring their parent ways).
- `tests` filters by substring of the test name; `max_findings` caps the list but the
  per-message summary always covers everything.
- `include_other: true` adds informational findings — only when you intend to act.
- `fix: true` applies automatic fixes, each as its own undo step. Findings that call
  themselves fixable but only offer the fix through a dialog come back as
  `fix_unavailable` rather than being silently skipped.
- `before_upload` mirrors JOSM's upload check, but **checks other plugins add through
  their own upload hook (PT_Assistant) are not reachable** — those appear only in JOSM's
  upload dialog.

## replace_geometry

Keeps the way's id, tags and history. The subtleties:

- Nodes that are **shared with another way or carry tags are never moved**.
- A shared node within `snap_m` (default 0.5 m) of a new vertex takes that vertex.
- One lying on a new segment within `glue_m` (default 2 cm) is inserted into it, so the
  connection to the neighbour survives.
- A new vertex coinciding with a node of another way reuses that node — a landuse
  outline drawn along a building glues to it instead of duplicating a node on top.
- Everything else drops out and is reported in
  `shared_or_tagged_nodes_left_out_of_way`. **Always read that field.** An `entrance=*`
  or the top node of a `highway=steps` that fell out must be moved onto the new ring and
  re-inserted with `update_way_nodes`, otherwise the entrance sits off the building.
- No node is ever repeated in the result.

## insert_node_in_way

- Refuses a node farther than `max_distance_m` (default 1 m) and **reports the measured
  distance**, which is a cheap way to check a gap.
- `snap_m > 0` first projects the node onto the way, so the way's shape does not change.
- `index` overrides the nearest-segment search: `0` prepends, the current node count
  appends. Both **extend** the way instead of splitting a segment — this is how you
  connect a loose end to a junction node without moving anything.

## split_way / reverse_way / merge_nodes

- `split_way`: the part chosen by `keep` (`longest` default, or `first`) keeps the id,
  tags and history; the rest become new ways and parent relations are updated. Warnings
  JOSM would raise in a dialog (uncertain member order, incomplete relations) come back
  in the result — read them.
- `reverse_way`: **refuses ways whose tags depend on direction** (`oneway`, `incline`,
  `:left`/`:right`, `:forward`/`:backward`) rather than producing wrong data.
  `skip_irreversible` reverses the rest and reports the refused ones with their tags.
- `merge_nodes`: groups with conflicting values for the same key, or with several
  relation members, are refused outright unless `skip_conflicts: true`. `keep_first`
  chooses the survivor; otherwise JOSM prefers an existing node over a new one. Find
  candidates with `find_duplicate_nodes`.

## reshape_area

Redraws a landuse-like area so given buildings fall outside (`exclude`) or inside
(`include`), moving ground to or from neighbouring areas so the tiling stays gap- and
overlap-free. Other buildings and areas are protected. **Always `dry_run` first.**
It refuses some configurations — where two areas share a node chain, polygon algebra on
the coordinates plus `replace_geometry` is the way through.

## update_node / update_nodes

Set **exact** lat/lon. The old MoveCommand went through the projection and left floating
point noise that defeats duplicate-node detection — do not reintroduce it by rounding.

## delete_elements

Removes objects from referencing ways and relations, like JOSM's Delete.
`delete_way_nodes: false` keeps untagged nodes of a deleted way — **use it whenever the
nodes are shared**, e.g. deleting a duplicate way that sits on a coastline.

## Layers

`get_josm_state`, `set_layer_visibility` and `move_layer` all report the stack with an
`index` per layer. **Index 0 is the top**, drawn over everything below it.

- `set_layer_visibility` shows, hides or sets the opacity of one layer.
- `move_layer` reorders. Give **exactly one** of `position` (absolute, out-of-range
  values clamp), `direction` (`up`, `down`, `top`, `bottom`), `above` or `below`
  (another layer by name). `unchanged: true` in the result means it was already there.
- Both match the layer by exact name or by a unique case-insensitive substring; an
  ambiguous substring is refused and the error names the layers that matched.
- A layer that renders nothing is usually **buried, not empty**. Check its index before
  concluding the source has no data there; move it up or hide what is above it.
- `remove_layer` refuses a data layer with unsaved changes unless forced, and never the
  active one. Prefer hiding or moving over removing.

## Downloads

- `download_area`: hard API limit of 0.25 square degrees. A village is plenty.
- `download_overpass`: for objects outside the loaded area, or a targeted query.
- `download_incomplete`: completes relation stubs. `get_josm_state` reports
  `incomplete_stubs`; a large count means relation members are unresolved and geometry
  checks against them will be wrong.

## Undo, sessions, restart

- `undo`/`redo` refuse commands not made through the plugin unless `force=true`. Every
  plugin command is marked "(MCP)" and `list_commands` flags each entry with
  `by_plugin`. Never force over the mapper's own edits.
- `save_layer` writes the active layer to `.osm` so pending edits survive a restart.
- **JOSM cannot reload a plugin at runtime.** After installing a new jar, `save_session`
  then `restart_josm`. While JOSM runs, a new jar must be copied as `josmmcp.jar.new`.

## Resources and prompts

Resources `josm://state` and `josm://selection` mirror the state and selection tools.
Four prompts describe tested workflows and are worth reading before reinventing one:
`review-area`, `bag-sync`, `surface-from-bgt`, `bus-stops-chb`.

## Transport

Stateless streamable HTTP: no progress notifications and no server-initiated updates,
so nothing arrives between calls. Long operations simply block.
