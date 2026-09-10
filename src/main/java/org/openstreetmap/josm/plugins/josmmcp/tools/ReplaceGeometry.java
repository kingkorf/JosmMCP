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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
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
 * Gives an existing way a new outline while keeping its id, tags and history.
 * <ul>
 * <li>Nodes shared with other ways or carrying tags ("fixed") are never moved. A fixed node within
 * {@code snap_m} of a new vertex takes that vertex's place (each fixed node and each vertex at most once);
 * a fixed node that lies on a new segment within {@code glue_m} is inserted into that segment, so
 * connections to neighbouring ways survive; other fixed nodes drop out of the way and are reported.</li>
 * <li>A vertex that coincides (within {@code glue_m}) with a node of another way reuses that node, so
 * the outline is glued to its neighbour instead of getting a duplicate node on top of it.</li>
 * <li>Untagged nodes used only by this way are moved to the remaining vertices (exact coordinates) or
 * deleted when surplus; further vertices get new nodes.</li>
 * <li>The result never contains the same node twice in a row.</li>
 * </ul>
 */
public class ReplaceGeometry extends BaseTool {
	static final double DEFAULT_SNAP_METRES = 0.5;
	static final double DEFAULT_GLUE_METRES = 0.02;

	@Override
	public String getName() {
		return "replace_geometry";
	}

	@Override
	public String getDescription() {
		return "Replace the outline of a way with the given coordinates while keeping the way's id, tags and history "
				+ "(like utilsplugin2's Replace Geometry). Nodes shared with other ways or tagged are never moved: one "
				+ "within snap_m of a new vertex takes that vertex, one lying on a new segment (within glue_m) is inserted "
				+ "so the connection survives, others drop out of the way and are reported. A vertex that coincides with a "
				+ "node of another way (within glue_m) reuses that node, so buildings and landuse glue together instead of "
				+ "getting duplicate nodes. Untagged nodes used only by this way are moved or reused, surplus ones deleted. "
				+ "Close the ring by repeating the first coordinate, or set closed=true.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("id", Map.of("type", "integer"));
		props.put("coordinates", Map.of("type", "array", "minItems", 2,
				"items", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 2, "maxItems", 2),
				"description", "New vertices as [lon, lat] pairs, in order"));
		props.put("closed", Map.of("type", "boolean", "description", "Close the way (default: closed if the way was closed)"));
		props.put("snap_m", Map.of("type", "number", "description",
				"A shared or tagged node of the way this close to a new vertex takes that vertex's place instead of "
						+ "being dropped (default " + DEFAULT_SNAP_METRES + " m). The vertex then sits at the node's position."));
		props.put("glue_m", Map.of("type", "number", "description",
				"Coincidence tolerance (default " + DEFAULT_GLUE_METRES + " m): a vertex this close to a node of another "
						+ "way reuses that node; a shared node of the way this close to a new segment is inserted into it. "
						+ "0 disables both."));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("id", "coordinates"), null, null, null);
	}

	// --- small planar geometry helpers (metres, locally around the way) ---------------------

	private static double[] xy(LatLon p, double refLat) {
		double k = 111_320d * Math.cos(Math.toRadians(refLat));
		return new double[] {p.lon() * k, p.lat() * 110_574d};
	}

	private static double dist(double[] a, double[] b) {
		return Math.hypot(a[0] - b[0], a[1] - b[1]);
	}

	/** Distance from p to segment ab and the position of the foot point along ab as a fraction 0..1. */
	private static double[] distToSegment(double[] p, double[] a, double[] b) {
		double dx = b[0] - a[0];
		double dy = b[1] - a[1];
		double len2 = dx * dx + dy * dy;
		double t = len2 == 0 ? 0 : ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2;
		t = Math.max(0, Math.min(1, t));
		double[] foot = {a[0] + t * dx, a[1] + t * dy};
		return new double[] {dist(p, foot), t};
	}

	private static double optionalMetres(Map<String, Object> args, String key, double dflt) throws Exception {
		Object v = args.get(key);
		if (v == null) {
			return dflt;
		}
		double d = toDouble(v, key);
		if (d < 0 || d > 50) {
			throw new Exception(key + " must be between 0 and 50 metres");
		}
		return d;
	}

	/** Commands and statistics of a planned outline replacement, not yet on the undo stack. */
	static final class Plan {
		final List<Command> commands = new ArrayList<>();
		final Map<String, Object> stats = new LinkedHashMap<>();
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		long id = getLong(args, "id");
		Way way = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
		if (way == null || way.isDeleted()) {
			throw new Exception("Way with id " + id + " not found");
		}
		double snap = optionalMetres(args, "snap_m", DEFAULT_SNAP_METRES);
		double glue = optionalMetres(args, "glue_m", DEFAULT_GLUE_METRES);

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
		Object closedObj = args.get("closed");
		Boolean closed = closedObj instanceof Boolean ? (Boolean) closedObj : null;

		Plan plan = plan(ds, way, targets, closed, snap, glue);
		String description = tr("Replace geometry of way {0}", id) + " " + COMMAND_MARKER;
		addCommand(new SequenceCommand(description, plan.commands, false), description);
		return JosmUtils.toJson(plan.stats);
	}

	/**
	 * Works out the commands that give {@code way} the outline {@code targets} (see the class comment for
	 * the rules). {@code closedArg} null means: closed if the way was closed or the ring repeats its first
	 * vertex. Nothing is executed or put on the undo stack.
	 */
	static Plan plan(DataSet ds, Way way, List<LatLon> targetsIn, Boolean closedArg, double snap, double glue)
			throws Exception {
		long id = way.getUniqueId();
		List<LatLon> targets = new ArrayList<>();
		for (LatLon ll : targetsIn) {
			if (targets.isEmpty() || !targets.get(targets.size() - 1).equalsEpsilon(ll)) {
				targets.add(ll);
			}
		}
		if (targets.size() < 2) {
			throw new Exception("coordinates must contain at least 2 distinct [lon, lat] pairs");
		}
		boolean wasClosed = way.isClosed();
		if (targets.size() > 2 && targets.get(0).equalsEpsilon(targets.get(targets.size() - 1))) {
			targets.remove(targets.size() - 1);
			wasClosed = true;
		}
		boolean closed = closedArg != null ? closedArg : wasClosed;
		if (closed && targets.size() < 3) {
			throw new Exception("a closed way needs at least 3 distinct vertices");
		}
		double refLat = targets.get(0).lat();
		List<double[]> txy = new ArrayList<>(targets.size());
		for (LatLon t : targets) {
			txy.add(xy(t, refLat));
		}

		// Classify the current nodes.
		List<Node> oldNodes = new ArrayList<>(new LinkedHashSet<>(way.getNodes()));
		List<Node> free = new ArrayList<>();
		List<Node> fixed = new ArrayList<>();
		for (Node n : oldNodes) {
			if (n.getCoor() == null) {
				continue;
			}
			boolean sharedOrTagged = n.isTagged() || n.getReferrers().size() > 1;
			(sharedOrTagged ? fixed : free).add(n);
		}

		// 1. Fixed nodes take the nearest vertex within snap_m; each vertex at most one node, nearest wins.
		Node[] slot = new Node[targets.size()];
		double[] slotDist = new double[targets.size()];
		Arrays.fill(slotDist, Double.MAX_VALUE);
		Set<Node> placedFixed = new HashSet<>();
		boolean changed = true;
		Set<Node> pending = new LinkedHashSet<>(fixed);
		while (changed && !pending.isEmpty()) {
			changed = false;
			for (Node f : new ArrayList<>(pending)) {
				double[] p = xy(f.getCoor(), refLat);
				int bestI = -1;
				double bestD = Double.MAX_VALUE;
				for (int i = 0; i < targets.size(); i++) {
					double d = dist(p, txy.get(i));
					if (d <= snap && d < bestD && d < slotDist[i]) {
						bestD = d;
						bestI = i;
					}
				}
				if (bestI >= 0) {
					Node evicted = slot[bestI];
					slot[bestI] = f;
					slotDist[bestI] = bestD;
					placedFixed.add(f);
					pending.remove(f);
					if (evicted != null) {
						placedFixed.remove(evicted);
						pending.add(evicted); // may find another vertex
					}
					changed = true;
				} else {
					pending.remove(f);
				}
			}
		}

		// 2. Remaining vertices: glue to another way's node, else move a free node, else create one.
		List<Command> cmds = new ArrayList<>();
		Set<Node> usedFree = new HashSet<>();
		Set<Node> wayNodeSet = new HashSet<>(oldNodes);
		int glued = 0;
		int moved = 0;
		int created = 0;
		int reusedFree = 0;
		for (int i = 0; i < targets.size(); i++) {
			if (slot[i] != null) {
				continue;
			}
			LatLon t = targets.get(i);
			if (glue > 0) {
				Node hit = null;
				double hitD = Double.MAX_VALUE;
				double dLat = glue / 110_574d * 1.5;
				double dLon = glue / (111_320d * Math.cos(Math.toRadians(t.lat()))) * 1.5;
				BBox box = new BBox(t.lon() - dLon, t.lat() - dLat, t.lon() + dLon, t.lat() + dLat);
				for (Node n : ds.searchNodes(box)) {
					if (n.isDeleted() || n.getCoor() == null || wayNodeSet.contains(n) || n.getReferrers().isEmpty()) {
						continue;
					}
					double d = dist(xy(n.getCoor(), refLat), txy.get(i));
					if (d <= glue && d < hitD) {
						hitD = d;
						hit = n;
					}
				}
				if (hit != null && !Arrays.asList(slot).contains(hit)) {
					slot[i] = hit;
					glued++;
					continue;
				}
			}
			Node best = null;
			double bestD = Double.MAX_VALUE;
			for (Node f : free) {
				if (usedFree.contains(f)) {
					continue;
				}
				double d = dist(xy(f.getCoor(), refLat), txy.get(i));
				if (d < bestD) {
					bestD = d;
					best = f;
				}
			}
			if (best != null) {
				usedFree.add(best);
				if (!best.getCoor().equalsEpsilon(t)) {
					// ChangeCommand keeps the exact lat/lon; MoveCommand would go through the projection
					// and leave floating point noise in the coordinates.
					Node movedNode = new Node(best);
					movedNode.setCoor(t);
					cmds.add(new ChangeCommand(best, movedNode));
					moved++;
				} else {
					reusedFree++;
				}
				slot[i] = best;
				continue;
			}
			Node n = new Node(t);
			cmds.add(new AddCommand(ds, n));
			slot[i] = n;
			created++;
		}

		// 3. Fixed nodes without a vertex: insert those lying on a new segment, report the rest.
		List<List<Node>> inserts = new ArrayList<>(targets.size());
		for (int i = 0; i < targets.size(); i++) {
			inserts.add(new ArrayList<>());
		}
		List<double[]> insertT = new ArrayList<>();
		Map<Node, Double> insertPos = new HashMap<>();
		int insertedOnSegment = 0;
		List<Long> dropped = new ArrayList<>();
		int segments = closed ? targets.size() : targets.size() - 1;
		for (Node f : fixed) {
			if (placedFixed.contains(f)) {
				continue;
			}
			double[] p = xy(f.getCoor(), refLat);
			int bestSeg = -1;
			double bestD = Double.MAX_VALUE;
			double bestT = 0;
			if (glue > 0) {
				for (int s = 0; s < segments; s++) {
					double[] dt = distToSegment(p, txy.get(s), txy.get((s + 1) % targets.size()));
					if (dt[0] <= glue && dt[0] < bestD) {
						bestD = dt[0];
						bestSeg = s;
						bestT = dt[1];
					}
				}
			}
			if (bestSeg >= 0) {
				inserts.get(bestSeg).add(f);
				insertPos.put(f, bestT);
				insertedOnSegment++;
			} else {
				dropped.add(f.getUniqueId());
			}
		}

		// 4. Assemble, without the same node twice in a row.
		List<Node> newList = new ArrayList<>();
		for (int i = 0; i < targets.size(); i++) {
			appendDistinct(newList, slot[i]);
			List<Node> ins = inserts.get(i);
			ins.sort((a, b) -> Double.compare(insertPos.get(a), insertPos.get(b)));
			for (Node n : ins) {
				appendDistinct(newList, n);
			}
		}
		if (closed) {
			if (newList.size() < 3) {
				throw new Exception("the new outline collapses to fewer than 3 distinct nodes; nothing was changed");
			}
			if (newList.get(newList.size() - 1) != newList.get(0)) {
				newList.add(newList.get(0));
			}
		} else if (newList.size() < 2) {
			throw new Exception("the new outline collapses to fewer than 2 distinct nodes; nothing was changed");
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
		Plan plan = new Plan();
		plan.commands.addAll(cmds);
		Map<String, Object> result = plan.stats;
		result.put("way", id);
		result.put("vertices", targets.size());
		result.put("nodes_in_way", newList.size() - (closed ? 1 : 0));
		result.put("closed", closed);
		result.put("nodes_moved", moved);
		result.put("nodes_reused_in_place", reusedFree);
		result.put("nodes_created", created);
		result.put("nodes_deleted", surplus.size());
		result.put("nodes_glued_to_other_ways", glued);
		result.put("shared_or_tagged_nodes_kept", placedFixed.size());
		result.put("shared_or_tagged_nodes_inserted_on_segments", insertedOnSegment);
		result.put("shared_or_tagged_nodes_left_out_of_way", dropped.size());
		if (!dropped.isEmpty()) {
			result.put("left_out_node_ids", dropped.size() > 100 ? dropped.subList(0, 100) : dropped);
		}
		return plan;
	}

	private static void appendDistinct(List<Node> list, Node n) {
		if (n != null && (list.isEmpty() || list.get(list.size() - 1) != n)) {
			list.add(n);
		}
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}

	@Override
	public boolean isDestructive() {
		return true; // surplus nodes are deleted
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		Object coords = args == null ? null : args.get("coordinates");
		int n = coords instanceof List ? ((List<?>) coords).size() : 0;
		return "Replace the outline of way " + (args == null ? "?" : args.get("id")) + " with " + n
				+ " new vertices (surplus untagged nodes of the way are deleted)";
	}
}
