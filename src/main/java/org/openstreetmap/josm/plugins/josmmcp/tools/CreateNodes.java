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

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.AddPrimitivesCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.NodeData;
import org.openstreetmap.josm.data.osm.PrimitiveData;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Creates many untagged nodes in one call and one undo step; the ids come back in input order so
 * they can be fed straight into create_way or update_way_nodes.
 */
public class CreateNodes extends BaseTool {

	/** Upper bound per call; keeps a single request (and undo step) manageable. */
	static final int MAX_NODES = 5000;

	@Override
	public String getName() {
		return "create_nodes";
	}

	@Override
	public String getDescription() {
		return "Create many untagged nodes at once (up to " + MAX_NODES + ") as a single undo step. Takes [lon, lat] "
				+ "pairs and returns the new node ids in the same order, ready for create_way or update_way_nodes. "
				+ "Prefer this over repeated create_node calls.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("coordinates", Map.of("type", "array",
				"description", "Vertices as [lon, lat] pairs, in the order the ids should come back",
				"items", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 2, "maxItems", 2),
				"minItems", 1, "maxItems", MAX_NODES));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("coordinates"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object coordsObj = requireArg(args, "coordinates");
		if (!(coordsObj instanceof List) || ((List<?>) coordsObj).isEmpty() || ((List<?>) coordsObj).size() > MAX_NODES) {
			throw new Exception("coordinates must hold 1 to " + MAX_NODES + " [lon, lat] pairs");
		}
		List<?> coords = (List<?>) coordsObj;
		List<PrimitiveData> data = new ArrayList<>(coords.size());
		List<Long> ids = new ArrayList<>(coords.size());
		for (Object o : coords) {
			if (!(o instanceof List) || ((List<?>) o).size() != 2) {
				throw new Exception("each coordinate must be [lon, lat]; nothing was created");
			}
			List<?> c = (List<?>) o;
			LatLon ll = new LatLon(toDouble(c.get(1), "lat"), toDouble(c.get(0), "lon"));
			if (!ll.isValid()) {
				throw new Exception("invalid coordinates: " + ll + "; nothing was created");
			}
			Node n = new Node(ll);
			NodeData nd = n.save();
			data.add(nd);
			ids.add(nd.getUniqueId());
		}
		addCommand(new AddPrimitivesCommand(data, ds), tr("Create {0} nodes", ids.size()));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("created", ids.size());
		result.put("ids", ids);
		return JosmUtils.toJson(result);
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
