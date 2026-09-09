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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
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
 * Gives an existing way a new outline while keeping its id, tags and history. Free nodes
 * (untagged, used by no other way) are moved or reused; nodes shared with other ways or
 * carrying tags are never moved: they stay in the way only if a new vertex lies on them.
 */
public class ReplaceGeometry extends BaseTool {
	private static final double SNAP_METRES = 0.5;

	@Override
	public String getName() {
		return "replace_geometry";
	}

	@Override
	public String getDescription() {
		return "Replace the outline of a way with the given coordinates while keeping the way's id, tags and history "
				+ "(like utilsplugin2's Replace Geometry). Untagged nodes used only by this way are moved or reused; "
				+ "nodes shared with other ways (fences, neighbouring buildings) or tagged nodes are left in place and only "
				+ "kept in the way when a new vertex lies within 0.5 m of them. Surplus free nodes are deleted. Close the "
				+ "ring by repeating the first coordinate, or set closed=true.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("id", Map.of("type", "integer"));
		props.put("coordinates", Map.of("type", "array", "minItems", 2,
				"items", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 2, "maxItems", 2),
				"description", "New vertices as [lon, lat] pairs, in order"));
		props.put("closed", Map.of("type", "boolean", "description", "Close the way (default: closed if the way was closed)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("id", "coordinates"), null, null, null);
	}

	@Override
	public boolean isWriteTool() {
		return true;
	}

	@Override
	public boolean isDestructive() {
		return true; // surplus nodes are deleted
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		long id = getLong(args, "id");
		Way way = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
		if (way == null) {
			throw new Exception("Way with id " + id + " not found");
		}
		Object coordsObj = requireArg(args, "coordinates");
		if (!(coordsObj instanceof List) || ((List<?>) coordsObj).size() < 2) {
			throw new Exception("coordinates must contain at least 2 [lon, lat] pairs");
		}
		List<LatLon> targets = new ArrayList<>();
		for (Object o : (List<?>) coordsObj) {
			if (!(o instanceof List) || ((List<?>) o).size() != 2) {
				throw new Exception("each coordinate must be [lon, lat]");
			}
			LatLon ll = new LatLon(toDouble(((List<?>) o).get(1), "lat"), toDouble(((List<?>) o).get(0), "lon"));
			if (!ll.isValid()) {
				throw new Exception("invalid coordinate " + ll);
			}
			targets.add(ll);
		}
		boolean wasClosed = way.isClosed();
		if (targets.size() > 2 && targets.get(0).equalsEpsilon(targets.get(targets.size() - 1))) {
			targets.remove(targets.size() - 1);
			wasClosed = true;
		}
		Object closedObj = args.get("closed");
		boolean closed = closedObj instanceof Boolean ? (Boolean) closedObj : wasClosed;
		if (closed && targets.size() < 3) {
			throw new Exception("a closed way needs at least 3 distinct vertices");
		}

		// Classify the current nodes.
		List<Node> oldNodes = new ArrayList<>(new java.util.LinkedHashSet<>(way.getNodes()));
		List<Node> free = new ArrayList<>();
		List<Node> fixed = new ArrayList<>();
		for (Node n : oldNodes) {
			boolean sharedOrTagged = n.isTagged() || n.getReferrers().size() > 1;
			(sharedOrTagged ? fixed : free).add(n);
		}

		List<Command> cmds = new ArrayList<>();
		List<Node> newList = new ArrayList<>();
		Set<Node> usedFree = new HashSet<>();
		int reusedFixed = 0;
		int moved = 0;
		int created = 0;
		for (LatLon t : targets) {
			// 1. a fixed node already at this vertex?
			Node hit = null;
			for (Node f : fixed) {
				if (f.getCoor() != null && f.getCoor().greatCircleDistance(t) <= SNAP_METRES) {
					hit = f;
					break;
				}
			}
			if (hit != null) {
				newList.add(hit);
				reusedFixed++;
				continue;
			}
			// 2. the nearest unused free node, moved to the vertex
			Node best = null;
			double bestD = Double.MAX_VALUE;
			for (Node f : free) {
				if (usedFree.contains(f) || f.getCoor() == null) {
					continue;
				}
				double d = f.getCoor().greatCircleDistance(t);
				if (d < bestD) {
					bestD = d;
					best = f;
				}
			}
			if (best != null) {
				usedFree.add(best);
				if (bestD > 0.005) {
					cmds.add(new MoveCommand(best, t));
					moved++;
				}
				newList.add(best);
				continue;
			}
			// 3. a new node
			Node n = new Node(t);
			cmds.add(new AddCommand(ds, n));
			newList.add(n);
			created++;
		}
		if (closed) {
			newList.add(newList.get(0));
		}
		cmds.add(new ChangeNodesCommand(ds, way, newList));

		List<OsmPrimitive> surplus = new ArrayList<>();
		for (Node f : free) {
			if (!usedFree.contains(f)) {
				surplus.add(f);
			}
		}
		if (!surplus.isEmpty()) {
			cmds.add(new DeleteCommand(ds, surplus));
		}
		int droppedFixed = 0;
		for (Node f : fixed) {
			if (!newList.contains(f)) {
				droppedFixed++;
			}
		}
		UndoRedoHandler.getInstance().add(new SequenceCommand(tr("Replace geometry of way {0} (MCP)", id), cmds, false));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("way", id);
		result.put("vertices", targets.size());
		result.put("closed", closed);
		result.put("nodes_moved", moved);
		result.put("nodes_created", created);
		result.put("nodes_deleted", surplus.size());
		result.put("shared_or_tagged_nodes_kept", reusedFixed);
		result.put("shared_or_tagged_nodes_left_out_of_way", droppedFixed);
		return JosmUtils.toJson(result);
	}
}
