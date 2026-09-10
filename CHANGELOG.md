# Changelog

All notable changes to JosmMCP. The format follows [Keep a Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

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
