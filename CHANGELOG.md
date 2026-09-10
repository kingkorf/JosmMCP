# Changelog

All notable changes to JosmMCP. The format follows [Keep a Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
