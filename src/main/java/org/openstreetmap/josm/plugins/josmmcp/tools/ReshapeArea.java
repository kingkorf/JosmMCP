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

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;
import org.openstreetmap.josm.plugins.josmmcp.utils.PlanarGeometry;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Moves buildings out of or into a landuse-like area by redrawing the area's outline around them, and
 * hands the freed or taken ground to the neighbouring areas so the tiling stays gap- and overlap-free.
 * The outlines are applied with the replace_geometry rules, so shared nodes and gluing are handled.
 */
public class ReshapeArea extends BaseTool {
	static final double DEFAULT_OFFSET = 0.5;
	/** Vertices closer than this to the line between their neighbours are dropped from computed rings. */
	static final double SIMPLIFY_METRES = 0.01;
	/** Rings smaller than this (square metres) are ignored as arithmetic slivers. */
	static final double SLIVER_M2 = 0.05;
	private static final String[] AREA_KEYS = {"landuse", "natural", "leisure", "amenity", "place", "man_made", "tourism"};

	@Override
	public String getName() {
		return "reshape_area";
	}

	@Override
	public String getDescription() {
		return "Redraw the outline of a closed area way (landuse, natural, ...) so that the given buildings lie "
				+ "completely outside (exclude) or inside (include) it, with offset_m of room around them. The freed "
				+ "ground goes to the neighbouring area that already holds most of the building (or touches it), and "
				+ "ground taken for an included building is removed from the neighbours it overlapped, so areas keep "
				+ "tiling without gaps or overlaps. Other buildings and other areas are never intruded upon (protect). "
				+ "Outlines are applied with the replace_geometry rules: shared nodes stay connected, vertices on other "
				+ "ways' nodes glue to them. Use dry_run=true first to see which ways would change and by how much. "
				+ "Refuses when a result would split into several parts or get a hole.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("id", Map.of("type", "integer", "description", "The closed area way to reshape"));
		props.put("exclude", Map.of("type", "array", "items", Map.of("type", "integer"),
				"description", "Ids of building ways that must end up outside the area"));
		props.put("include", Map.of("type", "array", "items", Map.of("type", "integer"),
				"description", "Ids of building ways that must end up inside the area"));
		props.put("offset_m", Map.of("type", "number", "description",
				"Room left between a building and the new outline (default " + DEFAULT_OFFSET + " m); where another "
						+ "building or area is that close, the outline follows its edge instead"));
		props.put("neighbours", Map.of("type", "boolean", "description",
				"Also reshape the neighbouring areas that gain or lose the ground (default true)"));
		props.put("protect", Map.of("type", "boolean", "description",
				"Never intrude into buildings or areas other than the ones being reshaped (default true)"));
		props.put("dry_run", Map.of("type", "boolean", "description", "Only report the planned changes (default false)"));
		props.put("snap_m", Map.of("type", "number", "description", "Passed to the outline replacement (default 0.5)"));
		props.put("glue_m", Map.of("type", "number", "description", "Passed to the outline replacement (default 0.02)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("id"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}

	@Override
	public boolean isDestructive() {
		return true; // surplus nodes of the reshaped ways are deleted
	}

	@Override
	protected boolean requiresConfirmation(Map<String, Object> args) {
		return !(args != null && Boolean.TRUE.equals(args.get("dry_run")));
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		Object ex = args == null ? null : args.get("exclude");
		Object in = args == null ? null : args.get("include");
		return "Reshape area " + (args == null ? "?" : args.get("id")) + " around " + (ex instanceof List ? ((List<?>) ex).size() : 0)
				+ " excluded and " + (in instanceof List ? ((List<?>) in).size() : 0)
				+ " included buildings, adjusting neighbouring areas (surplus nodes are deleted)";
	}

	private static boolean isBuilding(Way w) {
		return w.isClosed() && w.hasKey("building");
	}

	private static boolean isAreaWay(Way w) {
		if (!w.isClosed() || w.hasKey("building") || w.hasKey("highway") || w.hasKey("barrier")) {
			return false;
		}
		for (String k : AREA_KEYS) {
			if (w.hasKey(k)) {
				return true;
			}
		}
		return false;
	}

	private static List<Long> longs(Object o, String key) throws Exception {
		List<Long> out = new ArrayList<>();
		if (o == null) {
			return out;
		}
		if (!(o instanceof List)) {
			throw new Exception(key + " must be an array of way ids");
		}
		for (Object x : (List<?>) o) {
			out.add(toLong(x, key));
		}
		return out;
	}

	private static double metres(Map<String, Object> args, String key, double dflt, double max) throws Exception {
		Object v = args.get(key);
		if (v == null) {
			return dflt;
		}
		double d = toDouble(v, key);
		if (d < 0 || d > max) {
			throw new Exception(key + " must be between 0 and " + max + " metres");
		}
		return d;
	}

	private static Way closedWay(DataSet ds, long id, String role) throws Exception {
		Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
		if (w == null || w.isDeleted() || w.isIncomplete()) {
			throw new Exception(role + " way " + id + " not found");
		}
		if (!w.isClosed()) {
			throw new Exception(role + " way " + id + " is not closed");
		}
		return w;
	}

	private static Area union(List<Area> parts) {
		Area out = new Area();
		for (Area a : parts) {
			out.add(a);
		}
		return out;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		long id = getLong(args, "id");
		Way area = closedWay(ds, id, "area");
		if (isBuilding(area)) {
			throw new Exception("way " + id + " is a building; reshape_area works on landuse-like areas");
		}
		List<Long> excludeIds = longs(args.get("exclude"), "exclude");
		List<Long> includeIds = longs(args.get("include"), "include");
		if (excludeIds.isEmpty() && includeIds.isEmpty()) {
			throw new Exception("give at least one building in exclude or include");
		}
		double offset = metres(args, "offset_m", DEFAULT_OFFSET, 20);
		double snap = metres(args, "snap_m", ReplaceGeometry.DEFAULT_SNAP_METRES, 50);
		double glue = metres(args, "glue_m", ReplaceGeometry.DEFAULT_GLUE_METRES, 50);
		boolean withNeighbours = !Boolean.FALSE.equals(args.get("neighbours"));
		boolean protect = !Boolean.FALSE.equals(args.get("protect"));
		boolean dryRun = Boolean.TRUE.equals(args.get("dry_run"));

		List<Way> exclude = new ArrayList<>();
		List<Way> include = new ArrayList<>();
		Set<Long> named = new LinkedHashSet<>();
		for (long b : excludeIds) {
			if (!named.add(b)) {
				throw new Exception("building " + b + " listed twice");
			}
			exclude.add(closedWay(ds, b, "exclude"));
		}
		for (long b : includeIds) {
			if (!named.add(b)) {
				throw new Exception("building " + b + " listed in exclude and include");
			}
			include.add(closedWay(ds, b, "include"));
		}

		PlanarGeometry pg = new PlanarGeometry(area.getNode(0).lat());
		Area areaA = pg.area(area);

		// Everything that could take part: buildings and area ways around the area and the named buildings.
		BBox box = new BBox(area.getBBox());
		for (Way b : exclude) {
			box.add(b.getBBox());
		}
		for (Way b : include) {
			box.add(b.getBBox());
		}
		double grow = (offset + 1) / 100_000d;
		box = new BBox(box.getMinLon() - grow, box.getMinLat() - grow, box.getMaxLon() + grow, box.getMaxLat() + grow);
		List<Way> buildings = new ArrayList<>();
		List<Way> areaWays = new ArrayList<>();
		for (Way w : ds.searchWays(box)) {
			if (w.isDeleted() || w.isIncomplete() || w == area) {
				continue;
			}
			if (isBuilding(w)) {
				buildings.add(w);
			} else if (isAreaWay(w)) {
				areaWays.add(w);
			}
		}
		Map<Way, Area> areaOf = new HashMap<>();
		for (Way w : areaWays) {
			areaOf.put(w, pg.area(w));
		}
		Map<Way, Area> buildingOf = new HashMap<>();
		for (Way w : buildings) {
			buildingOf.put(w, pg.area(w));
		}
		for (Way b : exclude) {
			buildingOf.putIfAbsent(b, pg.area(b));
		}
		for (Way b : include) {
			buildingOf.putIfAbsent(b, pg.area(b));
		}

		// Who receives the ground of an excluded building, and who gives ground for an included one.
		Map<Way, Way> receiver = new LinkedHashMap<>();
		Map<Way, List<Way>> donors = new LinkedHashMap<>();
		Set<Way> affected = new LinkedHashSet<>();
		List<Map<String, Object>> buildingReport = new ArrayList<>();
		for (Way b : exclude) {
			Area ab = buildingOf.get(b);
			Area buf = PlanarGeometry.buffer(ab, offset);
			Way best = null;
			double bestOverlap = 0;
			for (Way w : areaWays) {
				Area x = new Area(areaOf.get(w));
				x.intersect(ab);
				double o = PlanarGeometry.squareMetres(x);
				if (o > bestOverlap) {
					bestOverlap = o;
					best = w;
				}
			}
			if (best == null) {
				for (Way w : areaWays) {
					Area x = new Area(areaOf.get(w));
					x.intersect(buf);
					double o = PlanarGeometry.squareMetres(x);
					if (o > bestOverlap) {
						bestOverlap = o;
						best = w;
					}
				}
			}
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("building", b.getUniqueId());
			r.put("action", "exclude");
			Area inA = new Area(ab);
			inA.intersect(areaA);
			r.put("share_in_area_before", Math.round(PlanarGeometry.squareMetres(inA) / Math.max(1e-9, PlanarGeometry.squareMetres(ab)) * 1000) / 10.0);
			if (best != null && withNeighbours) {
				receiver.put(b, best);
				affected.add(best);
				r.put("ground_goes_to", best.getUniqueId());
			} else {
				r.put("ground_goes_to", null);
			}
			buildingReport.add(r);
		}
		for (Way b : include) {
			Area ab = buildingOf.get(b);
			Area buf = PlanarGeometry.buffer(ab, offset);
			List<Way> ds2 = new ArrayList<>();
			for (Way w : areaWays) {
				Area x = new Area(areaOf.get(w));
				x.intersect(buf);
				if (PlanarGeometry.squareMetres(x) > 0.01) {
					ds2.add(w);
				}
			}
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("building", b.getUniqueId());
			r.put("action", "include");
			Area inA = new Area(ab);
			inA.intersect(areaA);
			r.put("share_in_area_before", Math.round(PlanarGeometry.squareMetres(inA) / Math.max(1e-9, PlanarGeometry.squareMetres(ab)) * 1000) / 10.0);
			List<Long> dl = new ArrayList<>();
			if (withNeighbours) {
				donors.put(b, ds2);
				for (Way w : ds2) {
					affected.add(w);
					dl.add(w.getUniqueId());
				}
			}
			r.put("ground_taken_from", dl);
			buildingReport.add(r);
		}

		// Protected ground: every building not being moved, and (with protect) every area not taking part.
		Set<Way> moving = new LinkedHashSet<>(exclude);
		moving.addAll(include);
		List<Area> protectedParts = new ArrayList<>();
		for (Map.Entry<Way, Area> e : buildingOf.entrySet()) {
			if (!moving.contains(e.getKey())) {
				protectedParts.add(e.getValue());
			}
		}
		if (protect) {
			for (Way w : areaWays) {
				if (!affected.contains(w)) {
					protectedParts.add(areaOf.get(w));
				}
			}
		}
		Area protectedGround = union(protectedParts);

		// The pieces: per excluded building its buffered footprint, per included building likewise.
		Map<Way, Area> piece = new LinkedHashMap<>();
		for (Way b : moving) {
			Area p = PlanarGeometry.buffer(buildingOf.get(b), offset);
			// buffers of moving buildings may overlap each other's footprints; that is intended (party walls)
			Area others = new Area(protectedGround);
			for (Way m : moving) {
				if (m != b && exclude.contains(b) != exclude.contains(m)) {
					others.add(buildingOf.get(m)); // an included and an excluded neighbour: the wall is the border
				}
			}
			p.subtract(others);
			piece.put(b, p);
		}

		// New outlines.
		Map<Way, Area> result = new LinkedHashMap<>();
		Area newA = new Area(areaA);
		for (Way b : exclude) {
			newA.subtract(piece.get(b));
		}
		for (Way b : include) {
			newA.add(piece.get(b));
		}
		result.put(area, newA);
		for (Way w : affected) {
			Area n = new Area(areaOf.get(w));
			for (Map.Entry<Way, Way> e : receiver.entrySet()) {
				if (e.getValue() == w) {
					Area gain = new Area(piece.get(e.getKey()));
					Area allowed = new Area(areaA);
					allowed.add(areaOf.get(w));
					gain.intersect(allowed); // only ground that was the area's or already its own
					n.add(gain);
				}
			}
			for (Map.Entry<Way, List<Way>> e : donors.entrySet()) {
				if (e.getValue().contains(w)) {
					n.subtract(piece.get(e.getKey()));
				}
			}
			result.put(w, n);
		}

		// Check shapes and turn them into rings.
		Map<Way, List<LatLon>> outlines = new LinkedHashMap<>();
		List<Map<String, Object>> wayReport = new ArrayList<>();
		for (Map.Entry<Way, Area> e : result.entrySet()) {
			Way w = e.getKey();
			Area a = e.getValue();
			// Slivers below a few square centimetres are arithmetic noise of coincident edges, not real parts.
			List<List<double[]>> rings = new ArrayList<>();
			for (List<double[]> ring : PlanarGeometry.rings(a)) {
				if (PlanarGeometry.ringArea(ring) > SLIVER_M2) {
					rings.add(ring);
				}
			}
			double before = PlanarGeometry.squareMetres(w == area ? areaA : areaOf.get(w));
			double after = PlanarGeometry.squareMetres(a);
			if (rings.size() != 1) {
				throw new Exception("reshaping would turn way " + w.getUniqueId() + " into " + rings.size()
						+ " rings (split or hole); nothing was changed. Handle that building by hand or leave it out.");
			}
			List<double[]> ring = PlanarGeometry.simplifyRing(rings.get(0), SIMPLIFY_METRES);
			ring = hugBuildings(ring, buildingOf.values(), pg, glue);
			if (ring.size() < 3) {
				throw new Exception("reshaping would collapse way " + w.getUniqueId() + "; nothing was changed");
			}
			// keep the way's original orientation and starting corner where possible
			List<LatLon> ll = new ArrayList<>();
			for (double[] p : ring) {
				ll.add(pg.toLatLon(p[0], p[1]));
			}
			if (!sameOrientation(w, ll, pg)) {
				java.util.Collections.reverse(ll);
			}
			rotateToStart(w, ll);
			outlines.put(w, ll);
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("way", w.getUniqueId());
			r.put("role", w == area ? "area" : receiver.containsValue(w) ? (isDonor(donors, w) ? "neighbour" : "receives ground") : "gives ground");
			r.put("tags", w.getKeys());
			r.put("area_m2_before", Math.round(before));
			r.put("area_m2_after", Math.round(after));
			r.put("vertices", ll.size());
			wayReport.add(r);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("dry_run", dryRun);
		out.put("buildings", buildingReport);
		out.put("ways", wayReport);
		if (dryRun) {
			return JosmUtils.toJson(out);
		}

		// Apply: the area first, then the neighbours, each seeing the previous result so they glue to it.
		List<Command> executed = new ArrayList<>();
		try {
			for (Map.Entry<Way, List<LatLon>> e : outlines.entrySet()) {
				ReplaceGeometry.Plan plan = ReplaceGeometry.plan(ds, e.getKey(), e.getValue(), Boolean.TRUE, snap, glue);
				SequenceCommand c = new SequenceCommand(tr("Replace geometry of way {0}", e.getKey().getUniqueId()), plan.commands, false);
				c.executeCommand();
				executed.add(c);
				for (Map<String, Object> r : wayReport) {
					if (((Number) r.get("way")).longValue() == e.getKey().getUniqueId()) {
						r.put("replace", plan.stats);
					}
				}
			}
		} catch (Exception ex) {
			for (int i = executed.size() - 1; i >= 0; i--) {
				executed.get(i).undoCommand();
			}
			throw ex;
		}
		for (int i = executed.size() - 1; i >= 0; i--) {
			executed.get(i).undoCommand();
		}
		String description = tr("Reshape area {0} around {1} buildings", id, moving.size()) + " " + COMMAND_MARKER;
		addCommand(new SequenceCommand(description, executed, false), description);
		return JosmUtils.toJson(out);
	}

	/** A ring vertex closer than this to a building wall is pushed to {@link #CLEARANCE} outside the wall. */
	static final double WALL_TOLERANCE = 0.03;
	static final double CLEARANCE = 0.03;
	/** A building corner this close to a ring segment is inserted into the ring (and later glued). */
	static final double CORNER_TOLERANCE = 0.1;

	/**
	 * Cleans up the millimetre noise the polygon arithmetic leaves where the ring runs along building walls:
	 * building corners that a segment passes within {@link #CORNER_TOLERANCE} become ring vertices (so they
	 * glue), and vertices that sit within {@link #WALL_TOLERANCE} of a wall without being a corner are moved
	 * {@link #CLEARANCE} outside the building. Otherwise JOSM reports the area as crossing the building.
	 */
	static List<double[]> hugBuildings(List<double[]> ring, java.util.Collection<Area> buildings, PlanarGeometry pg, double glue) {
		List<List<double[]>> walls = new ArrayList<>();
		List<double[]> corners = new ArrayList<>();
		for (Area b : buildings) {
			for (List<double[]> wall : PlanarGeometry.rings(b)) {
				walls.add(wall);
				corners.addAll(wall);
			}
		}
		// A. vertices within reach of a building corner take the corner's exact position
		List<double[]> snapped = new ArrayList<>();
		for (double[] v : ring) {
			double[] best = null;
			double bestD = CORNER_TOLERANCE;
			for (double[] c : corners) {
				double d = PlanarGeometry.dist(c, v);
				if (d <= bestD) {
					bestD = d;
					best = c;
				}
			}
			snapped.add(best != null ? new double[] {best[0], best[1]} : v);
		}
		List<double[]> out = dedupe(snapped);
		// B. corners that a segment passes closely become vertices of that segment (each corner once)
		for (double[] c : corners) {
			boolean present = false;
			for (double[] v : out) {
				if (PlanarGeometry.dist(v, c) <= 0.001) {
					present = true;
					break;
				}
			}
			if (present) {
				continue;
			}
			int bestSeg = -1;
			double bestD = CORNER_TOLERANCE;
			for (int i = 0; i < out.size(); i++) {
				double d = PlanarGeometry.distToSegment(c, out.get(i), out.get((i + 1) % out.size()));
				if (d <= bestD) {
					bestD = d;
					bestSeg = i;
				}
			}
			if (bestSeg >= 0) {
				out.add(bestSeg + 1, new double[] {c[0], c[1]});
			}
		}
		// C. vertices that are not corners but sit on a wall move a little off it
		List<double[]> result = new ArrayList<>();
		for (double[] v : out) {
			double[] moved = v;
			boolean isCorner = false;
			for (double[] c : corners) {
				if (PlanarGeometry.dist(c, v) <= 0.001) {
					isCorner = true;
					break;
				}
			}
			if (!isCorner) {
				for (Area b : buildings) {
					for (List<double[]> wall : PlanarGeometry.rings(b)) {
						for (int i = 0; i < wall.size(); i++) {
							double[] p = wall.get(i);
							double[] q = wall.get((i + 1) % wall.size());
							if (PlanarGeometry.distToSegment(moved, p, q) < WALL_TOLERANCE) {
								double dx = q[0] - p[0];
								double dy = q[1] - p[1];
								double len = Math.hypot(dx, dy);
								if (len == 0) {
									continue;
								}
								double t = ((moved[0] - p[0]) * dx + (moved[1] - p[1]) * dy) / (len * len);
								t = Math.max(0, Math.min(1, t));
								double[] foot = {p[0] + t * dx, p[1] + t * dy};
								double nx = -dy / len;
								double ny = dx / len;
								if (b.contains(foot[0] + nx * 0.05, foot[1] + ny * 0.05)) {
									nx = -nx;
									ny = -ny;
								}
								double[] cand = {foot[0] + nx * CLEARANCE, foot[1] + ny * CLEARANCE};
								boolean insideOther = false;
								for (Area o : buildings) {
									if (o != b && o.contains(cand[0], cand[1])) {
										insideOther = true;
									}
								}
								if (!insideOther) {
									moved = cand;
								}
							}
						}
					}
				}
			}
			result.add(moved);
		}
		return dedupe(result);
	}

	/** Removes consecutive near-duplicates and out-and-back spikes (a, b, a). */
	static List<double[]> dedupe(List<double[]> ring) {
		List<double[]> out = new ArrayList<>(ring);
		boolean changed = true;
		while (changed && out.size() > 3) {
			changed = false;
			for (int i = 0; i < out.size() && out.size() > 3; i++) {
				double[] prev = out.get((i - 1 + out.size()) % out.size());
				double[] cur = out.get(i);
				double[] next = out.get((i + 1) % out.size());
				if (PlanarGeometry.dist(prev, cur) <= 0.001) {
					out.remove(i);
					changed = true;
					break;
				}
				if (PlanarGeometry.dist(prev, next) <= 0.001) {
					// spike: drop the tip and one of the two coincident ends
					out.remove(i);
					out.remove(i % out.size());
					changed = true;
					break;
				}
			}
		}
		return out;
	}

	private static boolean isDonor(Map<Way, List<Way>> donors, Way w) {
		for (List<Way> l : donors.values()) {
			if (l.contains(w)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameOrientation(Way w, List<LatLon> ring, PlanarGeometry pg) {
		return signedArea(w.getNodes().stream().filter(n -> n.getCoor() != null).map(n -> pg.toXY(n.getCoor())).toList())
				* signedArea(ring.stream().map(pg::toXY).toList()) >= 0;
	}

	private static double signedArea(List<double[]> ring) {
		double s = 0;
		for (int i = 0, n = ring.size(); i < n; i++) {
			double[] p = ring.get(i);
			double[] q = ring.get((i + 1) % n);
			s += p[0] * q[1] - q[0] * p[1];
		}
		return s / 2;
	}

	/** Rotates the ring so that it starts at the vertex closest to the way's current first node. */
	private static void rotateToStart(Way w, List<LatLon> ring) {
		LatLon start = w.getNode(0).getCoor();
		if (start == null) {
			return;
		}
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < ring.size(); i++) {
			double d = ring.get(i).greatCircleDistance(start);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		java.util.Collections.rotate(ring, -best);
	}
}
