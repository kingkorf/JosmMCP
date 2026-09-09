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

import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Replaces the node list of a way (order, closure, membership). Nodes are not moved or deleted.
 */
public class UpdateWayNodes extends BaseTool {

	@Override
	public String getName() {
		return "update_way_nodes";
	}

	@Override
	public String getDescription() {
		return "Replace the complete node list of a way, in order. All nodes must exist in the dataset. Nodes that "
				+ "drop out of the way are kept in the dataset; use delete_node for them if they are no longer needed. "
				+ "For moving a whole outline to new coordinates use replace_geometry instead.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("id", Map.of("type", "integer"));
		props.put("node_ids", Map.of("type", "array", "items", Map.of("type", "integer"), "minItems", 2));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("id", "node_ids"), null, null, null);
	}

	@Override
	public boolean isWriteTool() {
		return true;
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
		Object idsObj = requireArg(args, "node_ids");
		if (!(idsObj instanceof List) || ((List<?>) idsObj).size() < 2) {
			throw new Exception("node_ids must be an array of at least 2 node ids");
		}
		List<Node> nodes = new ArrayList<>();
		for (Object o : (List<?>) idsObj) {
			long nid = toLong(o, "node_ids");
			Node n = (Node) ds.getPrimitiveById(new SimplePrimitiveId(nid, OsmPrimitiveType.NODE));
			if (n == null) {
				throw new Exception("Node with id " + nid + " not found");
			}
			nodes.add(n);
		}
		int before = w.getNodesCount();
		UndoRedoHandler.getInstance().add(new ChangeNodesCommand(ds, w, nodes));
		return "Way " + id + " now has " + nodes.size() + " nodes (was " + before + ")";
	}
}
