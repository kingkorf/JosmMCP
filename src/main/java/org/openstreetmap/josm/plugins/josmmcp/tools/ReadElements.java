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

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Reads many objects in one call; optionally with node coordinates for ways.
 */
public class ReadElements extends BaseTool {

	@Override
	public String getName() {
		return "read_elements";
	}

	@Override
	public String getDescription() {
		return "Read several objects at once ([{type, id}], up to 500). With include_nodes=true ways carry their node "
				+ "coordinates. Missing objects are listed separately instead of failing the whole call.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> el = new HashMap<>();
		el.put("type", "object");
		el.put("properties", Map.of("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")),
				"id", Map.of("type", "integer")));
		el.put("required", Arrays.asList("type", "id"));
		props.put("elements", Map.of("type", "array", "items", el, "minItems", 1, "maxItems", 500));
		props.put("include_nodes", Map.of("type", "boolean", "description", "Add nodes: [{id, lat, lon}] to ways (default false)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("elements"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	protected boolean supportsOutputPath() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object elsObj = requireArg(args, "elements");
		if (!(elsObj instanceof List) || ((List<?>) elsObj).isEmpty() || ((List<?>) elsObj).size() > 500) {
			throw new Exception("elements must be an array of 1 to 500 {type, id} objects");
		}
		boolean withNodes = Boolean.TRUE.equals(args.get("include_nodes"));
		List<Map<String, Object>> found = new ArrayList<>();
		List<Map<String, Object>> missing = new ArrayList<>();
		for (Object o : (List<?>) elsObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each element must be an object with type and id");
			}
			Map<?, ?> m = (Map<?, ?>) o;
			String type = String.valueOf(m.get("type"));
			long id = toLong(m.get("id"), "id");
			OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.from(type)));
			if (p == null || p.isIncomplete()) {
				missing.add(Map.of("type", type, "id", id));
				continue;
			}
			Map<String, Object> pm = JosmUtils.toMap(p);
			if (withNodes && p instanceof Way) {
				List<Map<String, Object>> nodes = new ArrayList<>();
				for (Node n : ((Way) p).getNodes()) {
					Map<String, Object> nm = new LinkedHashMap<>();
					nm.put("id", n.getUniqueId());
					if (n.getCoor() != null) {
						nm.put("lat", n.getCoor().lat());
						nm.put("lon", n.getCoor().lon());
					}
					nodes.add(nm);
				}
				pm.put("nodes", nodes);
			}
			found.add(pm);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("found", found.size());
		result.put("missing", missing);
		result.put("elements", found);
		return JosmUtils.toJson(result);
	}
}
