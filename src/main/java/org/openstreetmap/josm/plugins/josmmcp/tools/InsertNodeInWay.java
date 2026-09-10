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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.PlanarGeometry;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Puts an existing node into a way's node list at the segment it lies on, without resending the
 * whole list.
 *
 * <p>This is the operation behind "the entrance belongs on the building outline", "the steps end on
 * the wall" and "this road should join that one at a T": the node already exists and only its
 * membership of the way is missing.
 */
public class InsertNodeInWay extends BaseTool {
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final double DEFAULT_MAX_DISTANCE = 1.0;

	@Override
	public String getName() {
		return "insert_node_in_way";
	}

	@Override
	public String getDescription() {
		return "Insert an existing node into a way's node list at the segment it lies on, so an entrance, a gate, a "
				+ "stop or the end of a connecting way becomes part of an outline or a road without resending the "
				+ "whole node list. The node must lie within max_distance_m of the way (default 1 m), otherwise the "
				+ "call is refused and reports the measured distance. With snap_m greater than 0 a node that close to "
				+ "the way is first moved onto its projection on the way, so the way's shape does not change at all. "
				+ "Give 'index' to insert at an exact position instead; 0 prepends and the node count appends, which "
				+ "extends the way rather than splitting a segment.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("way_id", Map.of("type", "integer", "description", "The way to insert into"));
		props.put("node_id", Map.of("type", "integer", "description", "The existing node to insert"));
		props.put("index", Map.of("type", "integer", "description",
				"Insert at exactly this position instead of the nearest segment; 0 prepends, the current node count "
						+ "appends"));
		props.put("max_distance_m", Map.of("type", "number", "description",
				"Refuse when the node is farther than this from the way (default 1 m); ignored when 'index' is given"));
		props.put("snap_m", Map.of("type", "number", "description",
				"Move the node onto its projection on the way first when it is at most this far from it (default 0 = "
						+ "leave the node where it is)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("way_id", "node_id"), null, null, null);
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
		long wayId = getLong(args, "way_id");
		Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(wayId, OsmPrimitiveType.WAY));
		if (w == null) {
			throw new Exception("Way with id " + wayId + " not found");
		}
		if (w.isIncomplete()) {
			throw new Exception("Way " + wayId + " is not fully downloaded");
		}
		long nodeId = getLong(args, "node_id");
		Node n = (Node) ds.getPrimitiveById(new SimplePrimitiveId(nodeId, OsmPrimitiveType.NODE));
		if (n == null) {
			throw new Exception("Node with id " + nodeId + " not found");
		}
		if (n.getCoor() == null) {
			throw new Exception("Node " + nodeId + " has no coordinates");
		}
		if (w.containsNode(n)) {
			throw new Exception("Node " + nodeId + " is already a node of way " + wayId + " (at index "
					+ w.getNodes().indexOf(n) + ")");
		}
		if (w.getNodesCount() < 2) {
			throw new Exception("Way " + wayId + " has fewer than two nodes");
		}

		double maxDistance = args.get("max_distance_m") == null ? DEFAULT_MAX_DISTANCE
				: toDouble(args.get("max_distance_m"), "max_distance_m");
		double snap = args.get("snap_m") == null ? 0 : toDouble(args.get("snap_m"), "snap_m");
		if (maxDistance < 0 || snap < 0) {
			throw new Exception("max_distance_m and snap_m must not be negative");
		}

		List<Node> nodes = new ArrayList<>(w.getNodes());
		PlanarGeometry pg = new PlanarGeometry(n.lat());
		double[] p = pg.toXY(n.getCoor());

		int index;
		double distance;
		LatLon projection = null;
		Object indexObj = args.get("index");
		if (indexObj != null) {
			index = Math.toIntExact(toLong(indexObj, "index"));
			if (index < 0 || index > nodes.size()) {
				throw new Exception("index must be between 0 and " + nodes.size());
			}
			if (w.isClosed() && (index == 0 || index == nodes.size())) {
				throw new Exception("Way " + wayId + " is closed; inserting at " + index
						+ " would break the ring. Insert at a position inside the ring, or omit 'index'.");
			}
			distance = distanceToWay(pg, nodes, p);
		} else {
			// Nearest segment; ties go to the earlier segment, which keeps the insert stable.
			int best = -1;
			double bestDistance = Double.MAX_VALUE;
			for (int i = 0; i < nodes.size() - 1; i++) {
				if (nodes.get(i).getCoor() == null || nodes.get(i + 1).getCoor() == null) {
					continue;
				}
				double[] a = pg.toXY(nodes.get(i).getCoor());
				double[] b = pg.toXY(nodes.get(i + 1).getCoor());
				double d = PlanarGeometry.distToSegment(p, a, b);
				if (d < bestDistance) {
					bestDistance = d;
					best = i;
				}
			}
			if (best < 0) {
				throw new Exception("Way " + wayId + " has no segment with coordinates on both ends");
			}
			if (bestDistance > maxDistance) {
				throw new Exception(String.format(java.util.Locale.ROOT,
						"Node %d lies %.2f m from way %d, more than max_distance_m=%.2f. Check that it is the right "
								+ "way, or raise max_distance_m.",
						nodeId, bestDistance, wayId, maxDistance));
			}
			index = best + 1;
			distance = bestDistance;
			double[] a = pg.toXY(nodes.get(best).getCoor());
			double[] b = pg.toXY(nodes.get(best + 1).getCoor());
			double[] q = nearestPointOnSegment(p, a, b);
			projection = pg.toLatLon(q[0], q[1]);
		}

		List<Command> commands = new ArrayList<>();
		boolean snapped = false;
		if (snap > 0 && projection != null && distance <= snap && !projection.equals(n.getCoor())) {
			// ChangeCommand with a copy keeps the exact lat/lon, like update_node does.
			Node moved = new Node(n);
			moved.setCoor(projection);
			commands.add(new ChangeCommand(n, moved));
			snapped = true;
		}
		nodes.add(index, n);
		commands.add(new ChangeNodesCommand(ds, w, nodes));

		String description = tr("Insert node {0} in way {1}", n.getUniqueId(), w.getUniqueId());
		if (commands.size() == 1) {
			addCommand(commands.get(0), description);
		} else {
			addCommand(new SequenceCommand(description + " " + COMMAND_MARKER, commands, false), description);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("way", wayId);
		result.put("node", nodeId);
		result.put("index", index);
		result.put("distance_m", Math.rint(distance * 1000) / 1000);
		result.put("snapped", snapped);
		if (snapped) {
			result.put("moved_to", Arrays.asList(projection.lon(), projection.lat()));
		}
		result.put("nodes_before", w.getNodesCount() - 1);
		result.put("nodes_after", w.getNodesCount());
		result.put("node_is_tagged", n.isTagged());
		return JSON.writeValueAsString(result);
	}

	private static double distanceToWay(PlanarGeometry pg, List<Node> nodes, double[] p) {
		double best = Double.MAX_VALUE;
		for (int i = 0; i < nodes.size() - 1; i++) {
			if (nodes.get(i).getCoor() == null || nodes.get(i + 1).getCoor() == null) {
				continue;
			}
			best = Math.min(best, PlanarGeometry.distToSegment(p, pg.toXY(nodes.get(i).getCoor()),
					pg.toXY(nodes.get(i + 1).getCoor())));
		}
		return best;
	}

	/** The point on segment a-b closest to p. */
	private static double[] nearestPointOnSegment(double[] p, double[] a, double[] b) {
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double len2 = dx * dx + dy * dy;
		if (len2 == 0) {
			return new double[] {a[0], a[1]};
		}
		double t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2;
		t = Math.max(0, Math.min(1, t));
		return new double[] {a[0] + t * dx, a[1] + t * dy};
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
