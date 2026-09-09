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
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class ReadRelation extends BaseTool {

	@Override
	public String getName() {
		return "read_relation";
	}

	@Override
	public String getDescription() {
		return "Read a relation Id from current Dataset and returns its members and tags";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> readProps = new HashMap<>();
		Map<String, Object> idProp = new HashMap<>();
		idProp.put("type", "integer");
		readProps.put("id", idProp);
		readProps.put("include_geometry", Map.of("type", "boolean",
				"description", "Add coordinates to the members: nodes get lat/lon, ways get nodes: [{id, lat, lon}] (default false)"));
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
		Relation r = (Relation) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.RELATION));
		if (r == null) {
			throw new Exception("Relation with id " + id + " not found");
		}

		if (Boolean.TRUE.equals(args.get("include_geometry"))) {
			Map<String, Object> m = JosmUtils.toMap(r);
			List<Map<String, Object>> members = new ArrayList<>();
			for (org.openstreetmap.josm.data.osm.RelationMember rm : r.getMembers()) {
				Map<String, Object> mm = new LinkedHashMap<>();
				mm.put("type", rm.getType().getAPIName());
				mm.put("ref", rm.getUniqueId());
				mm.put("role", rm.getRole());
				if (rm.getMember().isIncomplete()) {
					mm.put("incomplete", true);
				} else if (rm.isNode() && rm.getNode().getCoor() != null) {
					mm.put("lat", rm.getNode().getCoor().lat());
					mm.put("lon", rm.getNode().getCoor().lon());
					if (rm.getNode().hasKeys()) {
						mm.put("tags", rm.getNode().getKeys());
					}
				} else if (rm.isWay()) {
					List<Map<String, Object>> nodes = new ArrayList<>();
					for (org.openstreetmap.josm.data.osm.Node n : rm.getWay().getNodes()) {
						Map<String, Object> nm = new LinkedHashMap<>();
						nm.put("id", n.getUniqueId());
						if (n.getCoor() != null) {
							nm.put("lat", n.getCoor().lat());
							nm.put("lon", n.getCoor().lon());
						}
						nodes.add(nm);
					}
					mm.put("nodes", nodes);
					if (rm.getWay().hasKeys()) {
						mm.put("tags", rm.getWay().getKeys());
					}
				}
				members.add(mm);
			}
			m.put("members", members);
			return JosmUtils.toJson(m);
		}
		return JosmUtils.printElement(r);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
