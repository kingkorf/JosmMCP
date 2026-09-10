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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Finds nodes that sit on the same spot (or within a small tolerance), the usual result of drawing an
 * outline along another object without gluing. Unlike the validator this looks at all nodes of the area,
 * not only the modified ones, and it reports mixed tagged/untagged groups too so merge_nodes can be fed.
 */
public class FindDuplicateNodes extends BaseTool {

	@Override
	public String getName() {
		return "find_duplicate_nodes";
	}

	@Override
	public String getDescription() {
		return "Find groups of nodes at the same position (or within tolerance_m of each other) in a bbox or the whole "
				+ "layer, including nodes that were not modified, so unglued outlines can be found and passed to "
				+ "merge_nodes. Each group lists the nodes with their tags and parent ways - each parent with its own "
				+ "tags, because whether a group should be merged depends on what the ways are: two landuse parcels "
				+ "meeting at a corner want a shared node, a river and an administrative boundary that touch do not. "
				+ "'mergeable' only means merging would raise no tag or relation conflict, not that it is correct. "
				+ "The result says whether merging "
				+ "would conflict (different tag values, or several nodes in relations).";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("bbox", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 4, "maxItems", 4,
				"description", "[min_lon, min_lat, max_lon, max_lat]; default: the whole layer"));
		props.put("tolerance_m", Map.of("type", "number", "description",
				"Nodes closer than this count as duplicates (default 0 = identical coordinates at OSM precision; max 5)"));
		props.put("untagged_only", Map.of("type", "boolean", "description",
				"Only report groups in which no node carries tags (default false)"));
		props.put("max_results", Map.of("type", "integer", "description", "Maximum number of groups to return (default 200)"));
		return new McpSchema.JsonSchema("object", props, null, null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	protected boolean supportsOutputPath() {
		return true;
	}

	/** Runs off the EDT under the read lock, like search_elements: the layer may hold a lot of nodes. */
	@Override
	protected List<McpSchema.Content> execute(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		ds.getReadLock().lock();
		try {
			return Arrays.asList(new McpSchema.TextContent(handle(args)));
		} finally {
			ds.getReadLock().unlock();
		}
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		double tolerance = 0;
		Object tolObj = args == null ? null : args.get("tolerance_m");
		if (tolObj != null) {
			tolerance = toDouble(tolObj, "tolerance_m");
			if (tolerance < 0 || tolerance > 5) {
				throw new Exception("tolerance_m must be between 0 and 5");
			}
		}
		boolean untaggedOnly = args != null && Boolean.TRUE.equals(args.get("untagged_only"));
		int maxResults = getInt(args, "max_results", 200);

		Collection<Node> candidates;
		Object bboxObj = args == null ? null : args.get("bbox");
		if (bboxObj != null) {
			if (!(bboxObj instanceof List) || ((List<?>) bboxObj).size() != 4) {
				throw new Exception("bbox must be [min_lon, min_lat, max_lon, max_lat]");
			}
			List<?> b = (List<?>) bboxObj;
			candidates = ds.searchNodes(new BBox(toDouble(b.get(0), "bbox"), toDouble(b.get(1), "bbox"),
					toDouble(b.get(2), "bbox"), toDouble(b.get(3), "bbox")));
		} else {
			candidates = ds.getNodes();
		}

		// Bucket by grid cell; cell size = tolerance (or one OSM precision step for exact matching).
		double cellLat = tolerance > 0 ? tolerance / 110_574d : LatLon.MAX_SERVER_PRECISION;
		Map<Long, List<Node>> grid = new HashMap<>();
		List<Node> nodes = new ArrayList<>();
		for (Node n : candidates) {
			if (n.isDeleted() || n.isIncomplete() || n.getCoor() == null) {
				continue;
			}
			nodes.add(n);
		}
		if (nodes.isEmpty()) {
			return JosmUtils.toJson(Map.of("groups", List.of(), "total_groups", 0));
		}
		double refLat = nodes.get(0).lat();
		double cellLon = tolerance > 0 ? tolerance / (111_320d * Math.cos(Math.toRadians(refLat))) : LatLon.MAX_SERVER_PRECISION;
		for (Node n : nodes) {
			grid.computeIfAbsent(cell(n.getCoor(), cellLat, cellLon), k -> new ArrayList<>()).add(n);
		}
		// Union-find over neighbouring cells.
		Map<Node, Node> parent = new HashMap<>();
		for (Node n : nodes) {
			parent.put(n, n);
		}
		double kLon = 111_320d * Math.cos(Math.toRadians(refLat));
		for (Node n : nodes) {
			LatLon c = n.getCoor();
			long cy = (long) Math.floor(c.lat() / cellLat);
			long cx = (long) Math.floor(c.lon() / cellLon);
			for (long dy = -1; dy <= 1; dy++) {
				for (long dx = -1; dx <= 1; dx++) {
					List<Node> cellNodes = grid.get(key(cy + dy, cx + dx));
					if (cellNodes == null) {
						continue;
					}
					for (Node m : cellNodes) {
						if (m == n || m.getUniqueId() < n.getUniqueId()) {
							continue;
						}
						boolean same;
						if (tolerance > 0) {
							double dxm = (m.lon() - c.lon()) * kLon;
							double dym = (m.lat() - c.lat()) * 110_574d;
							same = Math.hypot(dxm, dym) <= tolerance;
						} else {
							same = c.getRoundedToOsmPrecision().equals(m.getCoor().getRoundedToOsmPrecision());
						}
						if (same) {
							union(parent, n, m);
						}
					}
				}
			}
		}
		Map<Node, List<Node>> groups = new LinkedHashMap<>();
		for (Node n : nodes) {
			groups.computeIfAbsent(find(parent, n), k -> new ArrayList<>()).add(n);
		}

		List<Map<String, Object>> out = new ArrayList<>();
		int total = 0;
		int withConflicts = 0;
		for (List<Node> g : groups.values()) {
			if (g.size() < 2) {
				continue;
			}
			g.sort((a, b) -> Long.compare(a.getUniqueId(), b.getUniqueId()));
			boolean anyTagged = g.stream().anyMatch(Node::isTagged);
			if (untaggedOnly && anyTagged) {
				continue;
			}
			total++;
			Map<String, Object> gm = new LinkedHashMap<>();
			LatLon first = g.get(0).getCoor();
			gm.put("lat", first.lat());
			gm.put("lon", first.lon());
			List<Map<String, Object>> members = new ArrayList<>();
			for (Node n : g) {
				Map<String, Object> nm = new LinkedHashMap<>();
				nm.put("id", n.getUniqueId());
				if (n.isTagged()) {
					nm.put("tags", n.getKeys());
				}
				// Parent ways come with their tags: a caller cannot judge whether a group should be
				// merged from ids alone, and fetching them separately is a round trip per group.
				List<Map<String, Object>> ways = new ArrayList<>();
				int relations = 0;
				for (OsmPrimitive p : n.getReferrers()) {
					if (p instanceof Way) {
						Map<String, Object> wm = new LinkedHashMap<>();
						wm.put("id", p.getUniqueId());
						if (p.isTagged()) {
							wm.put("tags", p.getKeys());
						}
						ways.add(wm);
					} else {
						relations++;
					}
				}
				nm.put("ways", ways);
				if (relations > 0) {
					nm.put("relations", relations);
				}
				members.add(nm);
			}
			gm.put("nodes", members);
			String conflict = MergeNodes.conflict(g);
			gm.put("mergeable", conflict == null);
			if (conflict != null) {
				gm.put("conflict", conflict);
				withConflicts++;
			}
			if (out.size() < maxResults) {
				out.add(gm);
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("nodes_checked", nodes.size());
		result.put("tolerance_m", tolerance);
		result.put("total_groups", total);
		result.put("groups_with_conflicts", withConflicts);
		result.put("returned", out.size());
		result.put("truncated", out.size() < total);
		result.put("groups", out);
		return JosmUtils.toJson(result);
	}

	private static long cell(LatLon c, double cellLat, double cellLon) {
		return key((long) Math.floor(c.lat() / cellLat), (long) Math.floor(c.lon() / cellLon));
	}

	private static long key(long cy, long cx) {
		return (cy << 32) ^ (cx & 0xffffffffL);
	}

	private static Node find(Map<Node, Node> parent, Node n) {
		Node p = parent.get(n);
		while (p != n) {
			Node gp = parent.get(p);
			parent.put(n, gp);
			n = p;
			p = gp;
		}
		return n;
	}

	private static void union(Map<Node, Node> parent, Node a, Node b) {
		Node ra = find(parent, a);
		Node rb = find(parent, b);
		if (ra != rb) {
			parent.put(rb, ra);
		}
	}
}
