/*
 * JosmMCPPlugin - JOSM plugin to integrate JOSM with the Model Context Protocol
 * Copyright (C) 2025-2026 Pengunaria.dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.openstreetmap.josm.plugins.josmmcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.history.History;
import org.openstreetmap.josm.data.osm.history.HistoryDataSet;
import org.openstreetmap.josm.data.osm.history.HistoryNode;
import org.openstreetmap.josm.data.osm.history.HistoryOsmPrimitive;
import org.openstreetmap.josm.data.osm.history.HistoryWay;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.io.OsmServerHistoryReader;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Fetches the version history of an object from the OSM server.
 */
public class ReadHistory extends BaseTool {

	@Override
	public String getName() {
		return "read_history";
	}

	@Override
	public String getDescription() {
		return "Fetch the version history of an existing object from the OSM server: per version the timestamp, user, "
				+ "changeset, visibility, tags, and for nodes the coordinates, for ways the node count. Newest first, "
				+ "at most 'limit' versions (default 10). Needs network access; does not change anything.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")));
		props.put("id", Map.of("type", "integer"));
		props.put("limit", Map.of("type", "integer", "description", "Maximum number of versions, newest first (default 10)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("type", "id"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		throw new UnsupportedOperationException("use execute()");
	}

	@Override
	protected List<Content> execute(Map<String, Object> args) throws Exception {
		OsmPrimitiveType type = OsmPrimitiveType.from(requireArg(args, "type").toString());
		long id = getLong(args, "id");
		if (id <= 0) {
			throw new Exception("only objects that exist on the server have a history");
		}
		int limit = getInt(args, "limit", 10);
		// Network call: deliberately off the EDT.
		HistoryDataSet hds = new OsmServerHistoryReader(type, id).parseHistory(NullProgressMonitor.INSTANCE);
		History h = hds.getHistory(id, type);
		if (h == null) {
			throw new Exception(type.getAPIName() + " " + id + " has no history on the server");
		}
		List<Map<String, Object>> versions = new ArrayList<>();
		for (int i = h.getNumVersions() - 1; i >= 0 && versions.size() < limit; i--) {
			HistoryOsmPrimitive v = h.get(i);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("version", v.getVersion());
			m.put("timestamp", v.getInstant() == null ? null : v.getInstant().toString());
			m.put("user", v.getUser() == null ? null : v.getUser().getName());
			m.put("changeset", v.getChangesetId());
			m.put("visible", v.isVisible());
			m.put("tags", v.getTags());
			if (v instanceof HistoryNode && ((HistoryNode) v).getCoords() != null) {
				m.put("lat", ((HistoryNode) v).getCoords().lat());
				m.put("lon", ((HistoryNode) v).getCoords().lon());
			} else if (v instanceof HistoryWay) {
				m.put("node_count", ((HistoryWay) v).getNumNodes());
			}
			versions.add(m);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("type", type.getAPIName());
		result.put("id", id);
		result.put("num_versions", h.getNumVersions());
		result.put("versions", versions);
		return Arrays.asList(new TextContent(JosmUtils.toJson(result)));
	}
}
