# Changelog

All notable changes to JosmMCP. The format follows [Keep a Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

## [0.8.4] - 2026-09-11

### Changed
- `docs/agent-guide/`: the area-analysis recipe now puts one wide capture at step 3, before validating and counting. The rule to confirm the download covers the right place was already in step 1 but had no action attached to it, and the only call that shows it sat at the end; in Kuinre that meant a validate and two censuses over half a village before the mistake surfaced.

## [0.8.3] - 2026-09-11

### Changed
- `search_elements`: giving `ids` raises the default `max_results` to the number of ids. Asking for 70 specific ways returned 50 of them with `truncated: true`, which is a sensible guard for an open query and a trap for an explicit list. An explicit `max_results` still wins.
- `docs/agent-guide/`: "overlapping buildings" in a cadastral import is recorded as a known noise class, with the two checks that settle it - the overlap area, and how many pairs already share a node.

## [0.8.2] - 2026-09-11

### Changed
- `docs/agent-guide/`: the guidance on taginfo now covers both directions. It previously only warned that low usage exposes an invented tag; a high count is just as misleading the other way, because it says a tag exists and nothing about whether it belongs. `landuse=static_caravan` has 32 445 uses and only the wiki explains that it marks BAG plot outlines rather than buildings, which is what distinguishes a correct dual mapping from a tagging error.

## [0.8.1] - 2026-09-11

### Added
- `search_elements`: `group_by` returns how often each value of a tag key occurs among all matches, commonest first, plus `without_key`, and leaves the elements out of the result entirely. `max_results` and `offset` do not apply to the counting. This replaces the dump-to-file-and-script round trip that a tag census needed.
- `list_imagery`: entries report `has_bounds`, so an entry that matches `covering` because its coverage is unknown can be told from one that is known to cover the area.
- `docs/agent-guide/`: a closing section recording the order an area analysis actually follows, so that the register cross-check does not get skipped.

## [0.8.0] - 2026-09-11

### Added
- `list_imagery`: a `covering` filter taking `[lon, lat]` or a bbox, keeping only the entries whose declared coverage includes it; entries without bounds are worldwide and always match. `country` is too coarse wherever a country publishes imagery per region - filtering Belgium for a place in Wallonia returned 28 Flemish layers and none of the Walloon ones.
- `find_duplicate_nodes`: each parent way is now reported with its tags. Whether a group should be merged depends on what the ways are - two landuse parcels meeting at a corner want a shared node, a river and an administrative boundary that touch do not - and that could not be judged from the bare way ids the tool used to return.

### Changed
- `find_duplicate_nodes` documents that `mergeable` means merging would raise no tag or relation conflict, not that merging is correct.

## [0.7.1] - 2026-09-11

### Added
- `list_imagery`: when a `country` filter is given, the result reports `worldwide_also_matching` - how many entries the query matched that the filter hid because they carry no country code. Worldwide sources such as Esri are often the best aerial for a country whose own catalogue is thin and regional, and filtering by country made them silently invisible.

## [0.7.0] - 2026-09-10

### Added
- `remove_layer`: a `layers` list removes several layers under one confirmation dialog, all-or-nothing - everything is resolved and checked before the first removal, so naming the active data layer or one with unsaved changes leaves the whole stack untouched.
- `set_layer_visibility`, `move_layer` and `remove_layer` resolve a layer by the catalogue id it was created from as well as by its name, and the layer lists report that id as `imagery_id`. A layer is named after its *translated* catalogue name, so `add_imagery_layer` accepting an id was only half the story: `DE-NRW-DOP` now works end to end.

### Fixed
- The MapCSS validator test class was documented as `MapCSSTagChecker`; it is `MapCSSTagCheckerAndRule` with code 3000.

### Notes
- Exposing the matched MapCSS rule per finding was investigated and dropped. `TestError.getIgnoreGroup()` and `getIgnoreSubGroup()` embed the translated message, and the `rule` field of the tester does not correspond to the finding (a check for `building=construction` reported a tester holding a `barrier=kerb` rule), so there is no reliable per-finding handle for MapCSS checks beyond class and code. Read the flagged element's tags instead.

## [0.6.1] - 2026-09-10

### Fixed
- `list_imagery` searched only an entry's name, and catalogue names are translated into JOSM's interface language. On a Dutch JOSM a search for "NRW" returned nothing while the layer sat there as "Noordrijn-Westfalen luchtfoto's"; the query now matches the untranslated id as well, and `add_imagery_layer` accepts an exact id.

### Added
- `list_imagery`: a `country` filter taking an ISO 3166-1 alpha-2 code, so another country's layers can be found without guessing their translated name.
- `validate`: every finding carries `test_class` (the validator test's class name) and `code` beside the already present `test` and `message`. Those two are not translated, so a client can recognise a finding whatever language JOSM runs in; the message strings cannot be relied on for that. All MapCSS-based tag checks share the class `MapCSSTagCheckerAndRule` and code 3000.
- `get_josm_state`: reports JOSM's interface `locale`, so a client knows up front that validator messages, imagery names and preset names come back translated.
- `docs/agent-guide/german-sources.md`: a second worked example of verifying against a national register, for the NRW ALKIS WFS, including the trap that `ave:GebaeudeBauwerk` counts canopies and building parts as well as buildings.

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
