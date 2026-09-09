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
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class ReadWay extends BaseTool {

	@Override
	public String getName() {
		return "read_way";
	}

	@Override
	public String getDescription() {
		return "Read a way Id from current Dataset and returns its nodes and tags";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> readProps = new HashMap<>();
		Map<String, Object> idProp = new HashMap<>();
		idProp.put("type", "integer");
		readProps.put("id", idProp);
		readProps.put("include_nodes", Map.of("type", "boolean",
				"description", "Also return the coordinates of every node as nodes: [{id, lat, lon}] (default false)"));
		McpSchema.JsonSchema readSchema = new McpSchema.JsonSchema("object", readProps, Arrays.asList("id"), null, null,
				null);
		return readSchema;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}

		long id = getLong(args, "id");
		Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
		if (w == null) {
			throw new Exception("Way with id " + id + " not found");
		}

		if (Boolean.TRUE.equals(args.get("include_nodes"))) {
			Map<String, Object> m = JosmUtils.toMap(w);
			List<Map<String, Object>> nodes = new ArrayList<>();
			for (Node n : w.getNodes()) {
				Map<String, Object> nm = new LinkedHashMap<>();
				nm.put("id", n.getUniqueId());
				if (n.getCoor() != null) {
					nm.put("lat", n.getCoor().lat());
					nm.put("lon", n.getCoor().lon());
				}
				nodes.add(nm);
			}
			m.put("nodes", nodes);
			return JosmUtils.toJson(m);
		}
		return JosmUtils.printElement(w);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
