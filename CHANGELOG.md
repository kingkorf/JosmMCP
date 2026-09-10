# Changelog

All notable changes to JosmMCP. The format follows [Keep a Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

## [0.6.0] - 2026-09-10

### Added
- `move_layer`: reorder the layer stack, like dragging a layer in JOSM's layer list. Takes exactly one of `position` (absolute index, clamped), `direction` (`up`, `down`, `top`, `bottom`), `above` or `below` (another layer by name). Index 0 is the top of the stack, so this is what puts an overlay such as *BAG panden* above the imagery that was hiding it - until now the only way to see such a layer was to hide everything above it. The result lists every layer with its index.
- `docs/agent-guide/`: a client-neutral guide for LLM agents driving JOSM through this server - efficient use of the tools (result size, batching, the confirmation and undo guards), per-tool pitfalls, wiki-first tagging with the OSM good-practice and verifiability principles, and the Dutch registers. Any client can read the Markdown directly; `tools/sync-agent-skill.py` writes the same text plus frontmatter to an untracked `.claude/skills/osm-mapping/SKILL.md` for Claude Code, and `AgentGuideTest` keeps that copy honest when it is present.

### Changed
- `set_layer_visibility` now reports each layer's `index`, and shares its layer lookup with `move_layer`: an ambiguous substring names the layers that matched instead of only counting them.

## [0.5.0] - 2026-09-10

### Added
- `split_way`: split a way at nodes of its own, like JOSM's Split Way. The part chosen by `keep` (`longest` by default, or `first`) keeps the way's id, tags and history, the other parts become new ways with the same tags, and parent relations are updated. The warnings JOSM would raise in a dialog (uncertain relation member order, incomplete relations) are collected and returned, together with the parent relations and whether they have incomplete members.
- `reverse_way`: reverse the node order of one or more ways as a single undo step. Tags are left alone, so a way whose tags depend on its direction (`oneway`, `incline`, the `:left`/`:right` and `:forward`/`:backward` suffixes) is refused instead of being turned into wrong data; `skip_irreversible` reverses the others and reports the refused ones with their tags.
- `search_elements`: `include_geometry` adds `nodes: [{id, lat, lon}]` to every way in the result, so way outlines can be compared with an external source without reading each way separately; it survives a `fields` filter. Relations are not expanded (use `read_relation`), nodes already carried their coordinates.
- `search_elements`: `regex` reads the values in the query as regular expressions that must match the whole value (`"source:date"=2014.*`), and `case_sensitive` decides whether such a regex ignores case. Without `regex`, `key=value` stays JOSM's exact, case sensitive match, which has no wildcard - the query is now documented that way, because `source:date=2014` silently matching nothing on a value of `2014-03-24` reads like a bug.
- `insert_node_in_way`: put an existing node into a way's node list at the segment it lies on, without resending the whole list - for an `entrance` on a building outline, a gate on a road or the end of a connecting way at a T-junction. Refuses a node farther than `max_distance_m` (default 1 m) from the way and reports the measured distance; `snap_m` first moves a node that close onto its projection on the way, so the way's shape does not change. `index` inserts at an exact position instead.

### Fixed
- `update_relation_members` reported the new member count as the old one ("now has 28 members (was 28)"), because it read the count after the change command had already been applied.
- `validate` with `fix=true` silently skipped findings whose test reports `fixable` but returns no command because it only offers the fix through a dialog (PT_Assistant does this); the result showed `fixed: 0` without explanation. Those are now counted as `fix_unavailable` with a note.

## [0.4.0] - 2026-09-10

### Added
- `validate`: scopes `bbox` and `elements`, a `tests` name filter and `max_findings`.
- `search_elements`: `ids` parameter (query optional), combinable with the spatial filters.
- `find_orphan_nodes`: untagged nodes without parents, for cleaning up after geometry edits.
- `reshape_area`: move buildings out of or into a landuse-like area by redrawing its outline (and the neighbours' outlines) around them, with protection of other buildings and areas and a dry run.
- `find_duplicate_nodes` and `merge_nodes`: find nodes on the same spot across the whole layer and merge them in batches without dialogs; conflicting groups are reported.
- `replace_geometry`: `snap_m` and `glue_m` parameters; a vertex coinciding with a node of another way reuses that node (gluing to neighbours), shared nodes lying on a new segment are inserted into it, each shared node is used at most once so no node repeats, dropped shared nodes are reported by id, and moved nodes get exact coordinates.
- `create_nodes`, `update_nodes`, `delete_elements`: batch versions of the node and delete tools; one undo step, one confirmation, all or nothing.
- `output_path` on `search_elements`, `read_elements` and `validate`: write the full result to a file and return only a compact summary.

### Fixed
- `update_node` (and the new `update_nodes`) set the exact lat/lon; the former MoveCommand went through the projection and left floating point noise in the coordinates, which defeats duplicate-node detection.
- Undo descriptions of `modify_tags`, `modify_tags_batch` and `replace_geometry` were wrapped twice ("Sequence: Sequence: ...").

## [0.3.0] - 2026-09-10

### Added
- `undo`/`redo` refuse commands that were not made through the plugin unless `force=true`; `list_commands` marks each command with `by_plugin`.
- Confirmation dialog also for tag changes on relations with more than 100 members and for batches of more than 200 elements.
- `download_overpass`: run an Overpass QL query through JOSM's downloader into the active or a new layer.
- `search_elements`: `polygon` and `center`/`radius_m` filters for spatial matching against external geometries.
- `read_relation`: `include_geometry` returns member coordinates.
- `save_session`, `open_session`, `restart_josm` (the latter behind the confirmation dialog).
- Prompt `bus-stops-chb` describing how to check bus stops against the Dutch CHB register.

### Fixed
- Build from a fresh clone: the JOSM jar is fetched in a separate `mvn validate` run because Maven resolves dependencies before any plugin executes (the GitHub build failed on this).

### Changed
- Every command the plugin puts on the undo stack is now marked "(MCP)" in its description, so the undo guard recognises all of them.

## [0.2.1] - 2026-09-09

### Fixed
- Tools with an output schema always return structured content, also when the text result is truncated by the output limit (the MCP SDK rejected such results).

## [0.2.0] - 2026-09-09

### Added
- Tool groups (read, view, tags, geometry, delete, history, files, download) with per-group permissions in the preferences; MCP annotations `readOnlyHint` and `destructiveHint`.
- Confirmation dialog in JOSM before deletes, geometry replacement and layer removal, with timeout.
- Audit log `josmmcp-audit.log` of every modifying call.
- `download_area`, `download_incomplete`, `list_imagery`, `add_imagery_layer`, `remove_layer`.
- `pending_changes_summary`, `read_history`, `read_elements`.
- `capture_map_view`: `restore_view`.
- `search_elements` runs off the event dispatch thread under the dataset read lock.
- Output schemas for JSON tools; edit-tool tests on an in-memory layer; release workflow.

## [0.1.0] - 2026-09-09

### Added
- Security filter: Host and Origin checks, optional bearer token.
- Read-only mode and preferences panel.
- `save_layer`, `open_file`, `undo`, `redo`, `list_commands`, `revert_to_server`.
- `modify_tags_batch`, `update_way_nodes`, `replace_geometry`, `create_relation`, `select_elements`.
- `search_elements`: `bbox`, `fields`, `offset`; `read_way`: `include_nodes`; `validate`: `fix`, `before_upload`.
- Server assembly in `McpServerRunner` with resources `josm://state`, `josm://selection`, prompts and structured content.
- Integration tests against the real Jetty/MCP stack, CI workflow, plugin icon.

## [0.0.5] - 2026-09-09

### Added
- `capture_map_view`, `validate`, `set_layer_visibility`, `update_relation_members`.

### Changed
- Tool handlers run on the event dispatch thread; arguments are validated; deletes remove references like JOSM's Delete.
- Read tools return JSON; incomplete stubs are skipped.
- Build with maven-shade, Jetty 12, JUnit 5; server binds to loopback with a configurable port.
