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

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Moves many nodes in one call and one undo step.
 */
public class UpdateNodes extends BaseTool {

	static final int MAX_MOVES = 5000;

	@Override
	public String getName() {
		return "update_nodes";
	}

	@Override
	public String getDescription() {
		return "Move many nodes at once (up to " + MAX_MOVES + ") as a single undo step. Each move names a node id and "
				+ "its new lon/lat. All nodes must exist, otherwise nothing is moved. Prefer this over repeated "
				+ "update_node calls.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> move = new HashMap<>();
		move.put("type", "object");
		move.put("properties", Map.of("id", Map.of("type", "integer"), "lon", Map.of("type", "number"),
				"lat", Map.of("type", "number")));
		move.put("required", Arrays.asList("id", "lon", "lat"));
		props.put("moves", Map.of("type", "array", "items", move, "minItems", 1, "maxItems", MAX_MOVES));
		props.put("description", Map.of("type", "string", "description", "Short text for the undo history"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("moves"), null, null, null);
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
		Object movesObj = requireArg(args, "moves");
		if (!(movesObj instanceof List) || ((List<?>) movesObj).isEmpty() || ((List<?>) movesObj).size() > MAX_MOVES) {
			throw new Exception("moves must be an array of 1 to " + MAX_MOVES + " {id, lon, lat} objects");
		}
		List<Command> cmds = new ArrayList<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (Object o : (List<?>) movesObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each move must be an object with id, lon and lat");
			}
			Map<?, ?> m = (Map<?, ?>) o;
			long id = toLong(m.get("id"), "id");
			if (!seen.add(id)) {
				throw new Exception("node " + id + " appears twice; nothing was moved");
			}
			Node nd = (Node) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.NODE));
			if (nd == null || nd.isDeleted()) {
				throw new Exception("node " + id + " not found; nothing was moved");
			}
			LatLon ll = new LatLon(toDouble(m.get("lat"), "lat"), toDouble(m.get("lon"), "lon"));
			if (!ll.isValid()) {
				throw new Exception("invalid coordinates for node " + id + ": " + ll + "; nothing was moved");
			}
			// ChangeCommand with a copy keeps the exact lat/lon; MoveCommand would go through the
			// projection and leave floating point noise in the coordinates.
			Node moved = new Node(nd);
			moved.setCoor(ll);
			cmds.add(new ChangeCommand(nd, moved));
		}
		Object d = args.get("description");
		String description = (d != null && !d.toString().isBlank() ? d.toString() : tr("Move {0} nodes", cmds.size()))
				+ " " + COMMAND_MARKER;
		addCommand(new SequenceCommand(description, cmds), description);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("moved", cmds.size());
		return JosmUtils.toJson(result);
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
