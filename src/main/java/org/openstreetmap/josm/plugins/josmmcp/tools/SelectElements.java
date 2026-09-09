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
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Selects objects in JOSM (and optionally zooms to them) so the user sees what is being discussed.
 */
public class SelectElements extends BaseTool {

	@Override
	public String getName() {
		return "select_elements";
	}

	@Override
	public String getDescription() {
		return "Select the given objects in JOSM, replacing the current selection, so the user can see them. "
				+ "With zoom=true the map view is moved to the selection. Does not change data.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> el = new HashMap<>();
		el.put("type", "object");
		el.put("properties", Map.of("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")),
				"id", Map.of("type", "integer")));
		el.put("required", Arrays.asList("type", "id"));
		props.put("elements", Map.of("type", "array", "items", el, "description", "Objects to select; an empty array clears the selection"));
		props.put("zoom", Map.of("type", "boolean", "description", "Zoom the map view to the selection (default false)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("elements"), null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object elsObj = requireArg(args, "elements");
		if (!(elsObj instanceof List)) {
			throw new Exception("elements must be an array");
		}
		List<OsmPrimitive> prims = new ArrayList<>();
		for (Object o : (List<?>) elsObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each element must be an object with type and id");
			}
			Map<?, ?> m = (Map<?, ?>) o;
			long id = toLong(m.get("id"), "id");
			OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.from(String.valueOf(m.get("type")))));
			if (p == null) {
				throw new Exception(m.get("type") + " " + id + " not found in dataset");
			}
			prims.add(p);
		}
		ds.setSelected(prims);
		boolean zoom = Boolean.TRUE.equals(args.get("zoom"));
		if (zoom && !prims.isEmpty() && MainApplication.getMap() != null) {
			AutoScaleAction.zoomTo(prims);
		}
		return prims.isEmpty() ? "Selection cleared" : prims.size() + " object(s) selected" + (zoom ? " and zoomed to" : "");
	}

	@Override
	public Category category() {
		return Category.VIEW;
	}
}
