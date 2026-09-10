# JosmMCP

JosmMCP is a plugin for [JOSM](https://josm.openstreetmap.de/), the Java-based editor for OpenStreetMap. It runs a small HTTP server inside JOSM that speaks the [Model Context Protocol](https://modelcontextprotocol.io) (MCP), so AI assistants and other MCP clients can inspect and edit the data loaded in JOSM, with every change going through JOSM's undo/redo stack and never uploaded without the mapper.

## ⚠️ Status

Experimental. The plugin is usable for real editing sessions, but review every change in JOSM before uploading. It runs on loopback only by default, has an optional access token and a read-only mode, and has no user accounts.

## Usage

After installing and loading the plugin, JOSM starts an MCP server on `http://127.0.0.1:3000/mcp` (streamable HTTP, stateless). Point your MCP client at that URL. For Claude Code:

```bash
claude mcp add --transport http josm http://127.0.0.1:3000/mcp
```

With an access token configured, add `--header "Authorization: Bearer <token>"`.

### Settings

*Edit → Preferences → JosmMCP*, or the advanced preferences:

| Key | Default | Meaning |
|---|---|---|
| `josmmcp.host` | `127.0.0.1` | Bind address (restart required). Keep loopback unless you know what you are doing. |
| `josmmcp.port` | `3000` | TCP port (restart required) |
| `josmmcp.token` | empty | When set, every request needs `Authorization: Bearer <token>` (applies immediately) |
| `josmmcp.readonly` | `false` | Block all tools that modify data or files (applies immediately) |
| `josmmcp.max_output_chars` | `200000` | Truncate longer tool results with a notice; `0` disables |
| `josmmcp.confirm_destructive` | `true` | Ask in JOSM before deletes and geometry replacements |
| `josmmcp.confirm_timeout_seconds` | `60` | Deny automatically after this many seconds |
| `josmmcp.audit` | `true` | Append modifying calls to `josmmcp-audit.log` |
| `josmmcp.allow.<group>` | `true` | Enable a tool group: `view`, `tags`, `geometry`, `delete`, `history`, `files`, `download` |

### Security

- The `Host` header must name a loopback address (or the configured bind address), which blocks DNS-rebinding attacks.
- A browser-sent `Origin` header must be a loopback origin; other origins get `403`.
- With a token configured, requests without the correct bearer token get `401`.
- Tools that modify data carry the MCP annotation `readOnlyHint=false`; deleting tools also carry `destructiveHint=true`. In read-only mode they return an error instead of acting.
- There is deliberately no upload tool. Uploading stays a human action in JOSM.

## Tools

Read tools return JSON, also as MCP structured content with an output schema. Every edit is one or more JOSM undo steps.

`search_elements`, `read_elements`, `find_duplicate_nodes`, `find_orphan_nodes` and `validate` accept `output_path`: the full result is written to that `.json` or `.txt` file and only a compact summary (scalars, array lengths) is returned, so large results can be processed by a script instead of passing through the model. Writing needs the `files` tool group.

**Inspect**

* `get_josm_state` – version, layers (with visibility), counts and downloaded bounds of the active layer
* `get_user_selection` – the objects currently selected in JOSM
* `search_elements` – JOSM search syntax, with `bbox`, `polygon`, `center`/`radius_m`, `fields`, `offset` and `max_results`; `ids` restricts to given element ids (query then optional), so "which of these lie in this area" is one call; runs off the UI thread under the dataset's read lock
* `read_elements` – many objects in one call, optionally with node coordinates
* `read_history` – version history of an object from the OSM server
* `pending_changes_summary` – counts, tag keys, bbox and undo history of the pending changes, for drafting a changeset comment
* `select_elements` – select objects in JOSM (optionally zoom to them) so the mapper sees them
* `capture_map_view` – render the map view (data plus imagery) to an image, optionally zooming to an element or bbox first and restoring the view afterwards
* `set_layer_visibility` – show/hide a layer or set its opacity
* `search_elements` – JOSM search expressions with `bbox`, `polygon`, `center`+`radius_m`, `ids`, `fields`, `offset`/`max_results`. `key=value` is an exact, case sensitive match without wildcards, so `regex=true` reads the values as regular expressions that must match the whole value (`"source:date"=2014.*`); `case_sensitive` then decides whether that regex ignores case. `include_geometry` adds `nodes: [{id, lat, lon}]` to every way in the result, so outlines can be compared with an external source without reading each way separately
* `find_orphan_nodes` – untagged nodes used by no way or relation (by default only new or modified ones), ready for `delete_elements`
* `find_duplicate_nodes` – groups of nodes at the same position (or within `tolerance_m`) in a bbox or the whole layer, with tags, parent ways and whether merging would conflict
* `validate` – run JOSM's validator over the pending changes (including parent ways of moved nodes), the selection, the whole layer, a `bbox` or a list of `elements`; `tests` restricts to tests whose name contains a string, `max_findings` caps the list (the summary always covers all); `before_upload` mirrors JOSM's upload check; `fix` applies automatic fixes and reports as `fix_unavailable` the findings that call themselves fixable but only offer their fix through a dialog

**Nodes, ways, relations**

* `create_node`, `read_node`, `update_node` (move), `delete_node`
* `create_way`, `read_way` (with `include_nodes`), `update_way_nodes`, `replace_geometry`, `delete_way`
* `insert_node_in_way` – put an existing node into a way's node list at the segment it lies on, for an entrance on a building outline or the end of a connecting way on a road; refuses a node farther than `max_distance_m` (default 1 m) and with `snap_m` first moves it onto its projection so the outline keeps its shape
* `split_way` – split a way at nodes of its own, like JOSM's Split Way; the part chosen by `keep` (`longest` or `first`) keeps the id, tags and history, the rest become new ways and parent relations are updated. Warnings JOSM would show in a dialog come back in the result
* `reverse_way` – reverse the node order of one or more ways in one undo step. Ways whose tags depend on the direction (`oneway`, `incline`, `:left`/`:right`, ...) are refused, since reversing them without swapping those tags makes the data wrong; `skip_irreversible` reverses the rest and reports them
* `create_relation`, `read_relation` (with `include_geometry`), `update_relation_members`, `delete_relation`
* `reshape_area` – redraw a landuse-like area so that given buildings lie outside (`exclude`) or inside (`include`) it, with `offset_m` of room; the ground moves to or from the neighbouring areas so the tiling stays gap- and overlap-free, other buildings and areas are protected, `dry_run` previews

**Batches**

* `create_nodes` – many untagged nodes from `[lon, lat]` pairs, ids back in the same order
* `update_nodes` – move many nodes
* `delete_elements` – delete many objects of any type; one undo step and one confirmation dialog
* `merge_nodes` – merge groups of nodes into one node each, like JOSM's Merge Nodes, without dialogs: groups with tag or relation conflicts are refused or skipped

Each batch is a single undo step and is applied completely or not at all.

`replace_geometry` gives an existing way a new outline while keeping its id, tags and history. Nodes shared with other ways (fences, neighbours) or carrying tags are never moved: one within `snap_m` (default 0.5 m) of a new vertex takes that vertex, one lying on a new segment within `glue_m` (default 2 cm) is inserted into it so the connection to the neighbour survives, and the rest drop out of the way and are reported by id. A vertex that coincides with a node of another way (within `glue_m`) reuses that node, so a landuse outline drawn along a building glues to the building instead of getting a duplicate node on top of it. Untagged nodes used only by the way are moved to the remaining vertices (exact coordinates) or deleted when surplus; the result never repeats a node. Delete tools remove the object from referencing ways and relations like JOSM's Delete does.

**Tags**

* `modify_tags` – add, change or remove tags on one element (empty value removes)
* `modify_tags_batch` – the same for many elements as a single undo step

**Layers and downloads**

* `download_area` – download a bbox from the OSM server into the active or a new layer (API limit of 0.25 square degrees enforced)
* `download_incomplete` – complete relations or incomplete stubs
* `download_overpass` – run an Overpass QL query through JOSM's downloader, e.g. to fetch objects outside the loaded area
* `list_imagery`, `add_imagery_layer` – find and add aerial imagery or WMS/WMTS layers from JOSM's catalogue
* `remove_layer` – remove a layer; data layers with unsaved changes are refused unless forced, the active data layer never

**History and files**

* `undo`, `redo`, `list_commands` – JOSM's undo/redo stack; undo and redo refuse the mapper's own commands unless `force=true`
* `revert_to_server` – reload objects from the server, discarding local changes to them (File → Update selection)
* `save_layer` – write the active layer to an .osm file, so pending edits survive a restart
* `open_file` – open a local file as a new layer
* `save_session`, `open_session` – save or restore all layers, imagery included, as a JOSM session (.joz)
* `restart_josm` – restart JOSM, behind the confirmation dialog; combine with `save_session` when installing a new plugin jar

## Permissions, confirmation and audit

Every tool belongs to a group: read, view, tags, geometry, delete, history, files, download. In the preferences each group can be switched off, independently of the global read-only mode. Groups map to the MCP annotations `readOnlyHint` and `destructiveHint`.

By default JOSM shows a dialog before any delete, geometry replacement, layer removal or restart, and before tag changes that touch a relation with more than 100 members or more than 200 elements at once, naming the tool and the object, with Allow/Deny and a timeout (default 60 seconds, no answer means no). Denied calls return an error to the client and nothing changes.

Every modifying tool call is appended to `josmmcp-audit.log` in JOSM's user data directory: timestamp, tool, arguments, outcome. Both can be switched off in the preferences.

## Resources and prompts

Resources `josm://state` and `josm://selection` expose the same JSON as the corresponding tools. Four prompts describe tested workflows: `review-area` (measure completeness and propose improvements), `bag-sync` (synchronise buildings with the Dutch BAG register), `surface-from-bgt` (derive `surface` from the Dutch BGT) and `bus-stops-chb` (check bus stops against the Dutch CHB register).

## Limitations

* Validation checks that other plugins add through their own upload hook, such as PT_Assistant's route checks, are not reachable through `validate`; they only appear in JOSM's upload dialog.
* JOSM cannot reload a plugin at runtime. After installing a new jar, restart JOSM; save your layer first.
* The transport is stateless streamable HTTP: no progress notifications or server-initiated updates.
* Most tool calls run on JOSM's event dispatch thread; searches are the exception and run under a read lock.

## Building

Requirements: JDK 17 or newer and [Maven](https://maven.apache.org/).

```bash
mvn validate        # first time only: downloads josm-latest.jar into lib/
mvn clean package
```

JOSM is not published to Maven Central, so the `validate` phase downloads `josm-latest.jar` into the `lib` folder. This has to be a separate Maven run: Maven resolves all dependencies before the first plugin runs, so `mvn package` fails on a fresh clone until the jar is there. Delete the file to pick up a newer JOSM. The resulting plugin jar with all dependencies is `target/josmmcp.jar`. The test suite starts the real server on an ephemeral port and exercises the tool list, the security filter, read-only mode, resources and prompts, and runs the editing tools against an in-memory layer. Tagged commits `v*` are built and published as GitHub releases.

## Installing the plugin in JOSM

Copy `target/josmmcp.jar` to your JOSM plugins directory (`~/.josm/plugins/` or `~/.local/share/JOSM/plugins/`, depending on your installation). While JOSM is running, copy it as `josmmcp.jar.new` instead; JOSM swaps it in on the next start. Then enable it under *Edit → Preferences → Plugins*.

## License

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

---

**Disclaimer:** JOSM and OpenStreetMap are trademarks of their respective owners. This project is not affiliated with or endorsed by the JOSM or OpenStreetMap projects.

For any issues or contributions, please refer to the repository or open an issue.
