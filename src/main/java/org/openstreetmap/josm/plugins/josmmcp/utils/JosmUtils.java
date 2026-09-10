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
package org.openstreetmap.josm.plugins.josmmcp.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Helpers to turn JOSM primitives into JSON so MCP clients can parse tool output.
 */
public final class JosmUtils {
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private JosmUtils() {
	}

	/**
	 * Serialises any value (maps, lists, primitives) to pretty-printed JSON.
	 */
	public static String toJson(Object value) throws JsonProcessingException {
		return MAPPER.writeValueAsString(value);
	}

	/**
	 * Returns a JSON representation of the given primitive.
	 */
	public static String printElement(OsmPrimitive el) throws JsonProcessingException {
		return toJson(toMap(el));
	}

	/**
	 * Converts a primitive to an ordered map suitable for JSON serialisation.
	 */
	public static Map<String, Object> toMap(OsmPrimitive el) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", el.getUniqueId());
		m.put("type", el.getType().getAPIName());
		if (el.isIncomplete()) {
			m.put("incomplete", true);
		}
		if (el.isDeleted()) {
			m.put("deleted", true);
		}
		if (el.isModified()) {
			m.put("modified", true);
		}

		switch (el.getType()) {
		case NODE:
			LatLon ll = ((Node) el).getCoor();
			if (ll != null) {
				m.put("lat", ll.lat());
				m.put("lon", ll.lon());
			}
			break;
		case WAY:
			List<Long> nodeIds = new ArrayList<>();
			for (Node n : ((Way) el).getNodes()) {
				nodeIds.add(n.getUniqueId());
			}
			m.put("node_ids", nodeIds);
			break;
		case RELATION:
			List<Map<String, Object>> members = new ArrayList<>();
			for (RelationMember rm : ((Relation) el).getMembers()) {
				Map<String, Object> mm = new LinkedHashMap<>();
				mm.put("type", rm.getType().getAPIName());
				mm.put("ref", rm.getUniqueId());
				mm.put("role", rm.getRole());
				members.add(mm);
			}
			m.put("members", members);
			break;
		default:
			m.put("unsupported_type", el.getType().toString().toLowerCase(Locale.ROOT));
		}

		m.put("tags", el.getKeys());
		m.put("version", el.getVersion());
		m.put("user", el.getUser() == null ? null : el.getUser().getName());
		return m;
	}

	/** Ray-casting point-in-polygon test on lat/lon; the ring may or may not be closed. */
	/**
	 * Finds a layer by exact name, or by a unique case-insensitive substring of it.
	 *
	 * @param layers the layers to search, normally the layer manager's list
	 * @param wanted exact name or unique substring
	 * @return the matching layer, never null
	 * @throws Exception when nothing matches or the substring is ambiguous
	 */
	/**
	 * The catalogue id an imagery layer was created from, or null for other layers. A layer is
	 * named after its translated catalogue name, so the id is the only handle that survives a
	 * change of JOSM's interface language.
	 */
	public static String imageryId(Layer layer) {
		if (layer instanceof ImageryLayer) {
			ImageryInfo info = ((ImageryLayer) layer).getInfo();
			return info == null ? null : info.getId();
		}
		return null;
	}

	public static Layer findLayer(List<Layer> layers, String wanted) throws Exception {
		for (Layer l : layers) {
			if (l.getName().equals(wanted) || wanted.equals(imageryId(l))) {
				return l;
			}
		}
		List<Layer> matches = new ArrayList<>();
		String needle = wanted.toLowerCase(Locale.ROOT);
		for (Layer l : layers) {
			String id = imageryId(l);
			if (l.getName().toLowerCase(Locale.ROOT).contains(needle)
					|| (id != null && id.toLowerCase(Locale.ROOT).contains(needle))) {
				matches.add(l);
			}
		}
		if (matches.size() == 1) {
			return matches.get(0);
		}
		if (matches.isEmpty()) {
			throw new Exception("no layer matches '" + wanted + "'");
		}
		StringBuilder names = new StringBuilder();
		for (Layer l : matches) {
			if (names.length() > 0) {
				names.append(", ");
			}
			names.append('\'').append(l.getName()).append('\'');
		}
		throw new Exception("layer name '" + wanted + "' is ambiguous, " + matches.size() + " layers match: " + names);
	}

	/** Describes a layer for a tool result: name, type, visibility, opacity and its index in the stack. */
	public static List<Map<String, Object>> describeLayers(List<Layer> layers) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < layers.size(); i++) {
			Layer l = layers.get(i);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("index", i);
			m.put("name", l.getName());
			m.put("type", l.getClass().getSimpleName());
			String id = imageryId(l);
			if (id != null) {
				m.put("imagery_id", id);
			}
			m.put("visible", l.isVisible());
			m.put("opacity", l.getOpacity());
			out.add(m);
		}
		return out;
	}

	public static boolean pointInPolygon(double lat, double lon, List<LatLon> ring) {
		boolean inside = false;
		int n = ring.size();
		for (int i = 0, j = n - 1; i < n; j = i++) {
			LatLon a = ring.get(i);
			LatLon b = ring.get(j);
			if ((a.lat() > lat) != (b.lat() > lat)
					&& lon < (b.lon() - a.lon()) * (lat - a.lat()) / (b.lat() - a.lat()) + a.lon()) {
				inside = !inside;
			}
		}
		return inside;
	}

	/**
	 * Whether a primitive lies inside the polygon: a node by its position, a way when its
	 * centroid or any node is inside, a relation when any member node or way qualifies.
	 */
	public static boolean insidePolygon(OsmPrimitive p, List<LatLon> ring) {
		if (p instanceof Node) {
			LatLon c = ((Node) p).getCoor();
			return c != null && pointInPolygon(c.lat(), c.lon(), ring);
		}
		if (p instanceof Way) {
			double lat = 0;
			double lon = 0;
			int n = 0;
			for (Node nd : ((Way) p).getNodes()) {
				LatLon c = nd.getCoor();
				if (c == null) {
					continue;
				}
				if (pointInPolygon(c.lat(), c.lon(), ring)) {
					return true;
				}
				lat += c.lat();
				lon += c.lon();
				n++;
			}
			return n > 0 && pointInPolygon(lat / n, lon / n, ring);
		}
		if (p instanceof Relation) {
			for (RelationMember m : ((Relation) p).getMembers()) {
				if (!(m.getMember() instanceof Relation) && !m.getMember().isIncomplete() && insidePolygon(m.getMember(), ring)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Great-circle distance in metres from a point to the nearest node of the primitive (or its own position). */
	public static double distanceMetres(OsmPrimitive p, LatLon centre) {
		double best = Double.MAX_VALUE;
		if (p instanceof Node) {
			LatLon c = ((Node) p).getCoor();
			return c == null ? best : c.greatCircleDistance(centre);
		}
		if (p instanceof Way) {
			for (Node nd : ((Way) p).getNodes()) {
				if (nd.getCoor() != null) {
					best = Math.min(best, nd.getCoor().greatCircleDistance(centre));
				}
			}
			return best;
		}
		if (p instanceof Relation) {
			for (RelationMember m : ((Relation) p).getMembers()) {
				if (!(m.getMember() instanceof Relation) && !m.getMember().isIncomplete()) {
					best = Math.min(best, distanceMetres(m.getMember(), centre));
				}
			}
		}
		return best;
	}

	/** Parses [[lon, lat], ...] into a ring of at least 3 points. */
	public static List<LatLon> parseRing(Object coords) throws Exception {
		if (!(coords instanceof List) || ((List<?>) coords).size() < 3) {
			throw new Exception("coordinates must be an array of at least 3 [lon, lat] pairs");
		}
		List<LatLon> ring = new ArrayList<>();
		for (Object o : (List<?>) coords) {
			if (!(o instanceof List) || ((List<?>) o).size() != 2) {
				throw new Exception("each coordinate must be [lon, lat]");
			}
			List<?> c = (List<?>) o;
			LatLon ll = new LatLon(((Number) c.get(1)).doubleValue(), ((Number) c.get(0)).doubleValue());
			if (!ll.isValid()) {
				throw new Exception("invalid coordinate " + ll);
			}
			ring.add(ll);
		}
		return ring;
	}
}
