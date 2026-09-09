# JosmMCP

JosmMCP is a plugin for [JOSM](https://josm.openstreetmap.de/), the Java-based editor for OpenStreetMap. It integrates JOSM with the Model Context Protocol (MCP), providing an HTTP server that allows external MCP clients to access JOSM features.

## ⚠️ Warning

This plugin is currently in an experimental phase and is not suitable for production use. It serves as a proof-of-concept and does not adhere to zero trust principles. Users should be aware that it may lead to unexpected behaviors or errors.

## Usage

After installing and loading the plugin in JOSM, it automatically starts an MCP server on `127.0.0.1:3000`. External MCP clients (such as AI assistants or other applications) can connect to `http://localhost:3000/mcp` and use the available tools to interact with JOSM.

The server has no authentication, so it only listens on the loopback interface by default. Host and port can be changed in JOSM's advanced preferences:

* `josmmcp.host` (default `127.0.0.1`)
* `josmmcp.port` (default `3000`)

Currently available tools:

* `get_josm_state`: Retrieves the current state of JOSM, including version, loaded layers, and data statistics.
* `get_user_selection`: Retrieves the currently selected OSM elements in JOSM, including tags.
* `set_layer_visibility`: Shows or hides a layer (or sets its opacity), e.g. to look at imagery under an opaque overlay.
* `validate`: Runs JOSM's validator over the pending changes, the selection or the whole layer and returns errors and warnings.
* `capture_map_view`: Renders the map view (data plus visible imagery layers) to a JPEG/PNG image and returns it with the lat/lon bounds of the image. Can zoom to an element or bounding box first.
* `search_elements`: Searches for OSM elements in the downloaded data using JOSM query syntax (e.g., 'highway=residential', 'amenity=restaurant').
* `modify_tags`: Adds, changes or removes tags on an OSM element.
* `create_node`, `read_node`, `update_node`, `delete_node`
* `create_way`, `read_way`, `delete_way`
* `read_relation`, `update_relation_members`, `delete_relation`

Read tools return JSON. Every change goes through JOSM's undo/redo stack, so it can be reverted with *Edit → Undo*. Deleting an element also removes it from the ways and relations that reference it.

## Building

Requirements: JDK 17 or newer and [Maven](https://maven.apache.org/).

```bash
mvn clean package
```

The first build downloads `josm-latest.jar` into the `lib` folder (JOSM is not published to Maven Central). Delete that file to pick up a newer JOSM. The resulting plugin jar with all dependencies is `target/josmmcp.jar`.

## Installing the plugin in JOSM

Copy the generated jar from `target/` to your JOSM plugins directory, for example:

```bash
cp target/josmmcp.jar ~/.local/share/JOSM/plugins/
```

## License

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

---

**Disclaimer:** JOSM and OpenStreetMap are trademarks of their respective owners. This project is not affiliated with or endorsed by the JOSM or OpenStreetMap projects.

For any issues or contributions, please refer to the repository or open an issue.
