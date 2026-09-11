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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.search.SearchCompiler;
import org.openstreetmap.josm.data.osm.search.SearchMode;
import org.openstreetmap.josm.data.osm.search.SearchSetting;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

public class SearchTool extends BaseTool {

	@Override
	public String getName() {
		return "search_elements";
	}

	@Override
	public String getDescription() {
		return "Search for OSM elements in downloaded data using JOSM query syntax";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> searchProps = new HashMap<>();
		Map<String, Object> queryProp = new HashMap<>();
		queryProp.put("type", "string");
		queryProp.put("description", "JOSM search expression, e.g. 'highway=residential' or 'amenity=restaurant name:pizza'. "
				+ "key=value is an exact, case sensitive match and knows no wildcard: 'source:date=2014' does not "
				+ "match '2014-03-24'. Use regex=true with a regular expression for that");
		searchProps.put("query", queryProp);
		Map<String, Object> maxResultsProp = new HashMap<>();
		maxResultsProp.put("type", "integer");
		maxResultsProp.put("description", "Maximum number of elements to return (default 50, or the number of 'ids' when that is larger)");
		searchProps.put("max_results", maxResultsProp);
		searchProps.put("offset", Map.of("type", "integer", "description", "Skip this many matches first, for paging (default 0)"));
		searchProps.put("group_by", Map.of("type", "string", "description",
				"Instead of the elements, return how often each value of this tag key occurs among all matches, "
						+ "commonest first, plus how many matches lack the key. Answers 'what kinds of thing are here' "
						+ "(group_by 'building' over a town) without the elements passing through the conversation. "
						+ "Counts every match, so max_results and offset do not apply"));
		searchProps.put("bbox", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 4, "maxItems", 4,
				"description", "Only elements intersecting [min_lon, min_lat, max_lon, max_lat]"));
		searchProps.put("polygon", Map.of("type", "array", "minItems", 3,
				"items", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 2, "maxItems", 2),
				"description", "Only elements inside this polygon, as [lon, lat] pairs (a node by position, a way by any node or its centroid)"));
		searchProps.put("center", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 2, "maxItems", 2,
				"description", "With radius_m: only elements within that distance of [lon, lat]"));
		searchProps.put("radius_m", Map.of("type", "number", "description", "Search radius in metres around 'center'"));
		searchProps.put("fields", Map.of("type", "array", "items", Map.of("type", "string"),
				"description", "Only include these fields per element, e.g. [\"id\",\"type\",\"tags\"]; omit node_ids/members to keep results small"));
		searchProps.put("regex", Map.of("type", "boolean",
				"description", "Read the values in 'query' as regular expressions that must match the whole value, "
						+ "so '\"source:date\"=2014.*' finds every 2014 date and 'name=B.*blom' every such name "
						+ "(default false). Round brackets cannot be used: JOSM's query tokenizer splits on them, "
						+ "so write character classes instead of alternations"));
		searchProps.put("case_sensitive", Map.of("type", "boolean",
				"description", "Only has an effect together with regex: a regular expression ignores case unless "
						+ "this is true (default false). Without regex, key=value is always an exact, case "
						+ "sensitive match"));
		searchProps.put("include_geometry", Map.of("type", "boolean",
				"description", "Add nodes: [{id, lat, lon}] to every way in the result, so way outlines can be "
						+ "measured without reading the elements separately; nodes already carry lat/lon. Relations "
						+ "are not expanded, use read_relation with include_geometry for those. Costs about 40 bytes "
						+ "per node, so combine it with fields and max_results, or write it to output_path"));
		searchProps.put("ids", Map.of("type", "array", "items", Map.of("type", "integer"), "maxItems", 5000,
				"description", "Only these element ids (any type); with ids the query is optional and bbox/polygon/center "
						+ "still filter, so 'which of these ids lie in this area' is one call. Giving ids also raises the "
						+ "default max_results to the number of ids, so an explicit list is not silently cut to 50"));
		McpSchema.JsonSchema searchSchema = new McpSchema.JsonSchema("object", searchProps, null, null, null, null);
		return searchSchema;
	}

	/**
	 * Searching a large dataset can take a while; do it on the request thread under the
	 * dataset's read lock instead of blocking the UI on the EDT.
	 */
	@Override
	protected List<Content> execute(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		ds.getReadLock().lock();
		try {
			return Arrays.asList(new TextContent(handle(args)));
		} finally {
			ds.getReadLock().unlock();
		}
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		List<Long> ids = new ArrayList<>();
		if (args.get("ids") instanceof List) {
			for (Object o : (List<?>) args.get("ids")) {
				ids.add(toLong(o, "ids"));
			}
		}
		Object queryObj = args.get("query");
		if (queryObj == null && ids.isEmpty()) {
			throw new Exception("missing required argument 'query' (or give ids)");
		}
		String query = queryObj == null ? "*" : queryObj.toString();
		// With 'ids' the caller has already named exactly what it wants, so the page size defaults to
		// that count instead of 50: silently dropping half of an explicit list is a trap, not a guard.
		int maxResults = getInt(args, "max_results", ids.isEmpty() ? 50 : Math.max(50, ids.size()));
		if (maxResults < 0) {
			throw new Exception("max_results must not be negative");
		}

		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}

		Object gb = args == null ? null : args.get("group_by");
		String groupBy = gb == null ? null : gb.toString().trim();
		if (groupBy != null && groupBy.isEmpty()) {
			throw new Exception("group_by must be a tag key");
		}
		int offset = getInt(args, "offset", 0);
		if (offset < 0) {
			throw new Exception("offset must not be negative");
		}
		Set<String> fields = null;
		Object fieldsObj = args.get("fields");
		if (fieldsObj instanceof List && !((List<?>) fieldsObj).isEmpty()) {
			fields = new HashSet<>();
			for (Object f : (List<?>) fieldsObj) {
				fields.add(String.valueOf(f));
			}
			fields.add("id");
			fields.add("type");
		}
		boolean includeGeometry = Boolean.TRUE.equals(args.get("include_geometry"));
		if (includeGeometry && fields != null) {
			// the geometry was asked for explicitly; do not let 'fields' drop it again
			fields.add("nodes");
		}

		SearchCompiler.Match matcher = null;
		if (queryObj != null) {
			boolean regex = Boolean.TRUE.equals(args.get("regex"));
			boolean caseSensitive = Boolean.TRUE.equals(args.get("case_sensitive"));
			if (regex || caseSensitive) {
				SearchSetting setting = new SearchSetting();
				setting.text = query;
				setting.mode = SearchMode.replace;
				setting.regexSearch = regex;
				setting.caseSensitive = caseSensitive;
				matcher = SearchCompiler.compile(setting);
			} else {
				matcher = SearchCompiler.compile(query);
			}
		}
		Collection<OsmPrimitive> candidates;
		Object bboxObj = args.get("bbox");
		if (!ids.isEmpty()) {
			List<OsmPrimitive> c = new ArrayList<>();
			for (long id : ids) {
				for (org.openstreetmap.josm.data.osm.OsmPrimitiveType t : org.openstreetmap.josm.data.osm.OsmPrimitiveType.dataValues()) {
					OsmPrimitive p = ds.getPrimitiveById(new org.openstreetmap.josm.data.osm.SimplePrimitiveId(id, t));
					if (p != null) {
						c.add(p);
					}
				}
			}
			candidates = c;
		} else if (bboxObj != null) {
			if (!(bboxObj instanceof List) || ((List<?>) bboxObj).size() != 4) {
				throw new Exception("bbox must be [min_lon, min_lat, max_lon, max_lat]");
			}
			List<?> b = (List<?>) bboxObj;
			BBox bbox = new BBox(toDouble(b.get(0), "bbox"), toDouble(b.get(1), "bbox"), toDouble(b.get(2), "bbox"),
					toDouble(b.get(3), "bbox"));
			if (!bbox.isValid()) {
				throw new Exception("bbox is not valid");
			}
			List<OsmPrimitive> c = new ArrayList<>();
			c.addAll(ds.searchNodes(bbox));
			c.addAll(ds.searchWays(bbox));
			c.addAll(ds.searchRelations(bbox));
			candidates = c;
		} else {
			candidates = ds.allPrimitives();
		}
		List<org.openstreetmap.josm.data.coor.LatLon> ring = null;
		Object polyObj = args.get("polygon");
		if (polyObj != null) {
			ring = JosmUtils.parseRing(polyObj);
			if (bboxObj == null) {
				// restrict candidates to the polygon's bounding box first
				double minLat = 90, minLon = 180, maxLat = -90, maxLon = -180;
				for (org.openstreetmap.josm.data.coor.LatLon ll : ring) {
					minLat = Math.min(minLat, ll.lat()); maxLat = Math.max(maxLat, ll.lat());
					minLon = Math.min(minLon, ll.lon()); maxLon = Math.max(maxLon, ll.lon());
				}
				BBox pb = new BBox(minLon, minLat, maxLon, maxLat);
				List<OsmPrimitive> c = new ArrayList<>();
				c.addAll(ds.searchNodes(pb));
				c.addAll(ds.searchWays(pb));
				c.addAll(ds.searchRelations(pb));
				candidates = c;
			}
		}
		org.openstreetmap.josm.data.coor.LatLon centre = null;
		double radius = 0;
		Object centreObj = args.get("center");
		if (centreObj != null || args.get("radius_m") != null) {
			if (!(centreObj instanceof List) || ((List<?>) centreObj).size() != 2 || args.get("radius_m") == null) {
				throw new Exception("center [lon, lat] and radius_m must be given together");
			}
			List<?> cl = (List<?>) centreObj;
			centre = new org.openstreetmap.josm.data.coor.LatLon(toDouble(cl.get(1), "center"), toDouble(cl.get(0), "center"));
			radius = toDouble(args.get("radius_m"), "radius_m");
			if (!centre.isValid() || radius <= 0) {
				throw new Exception("center must be valid and radius_m positive");
			}
			if (bboxObj == null && ring == null) {
				double dLat = radius / 111320.0;
				double dLon = radius / (111320.0 * Math.cos(Math.toRadians(centre.lat())));
				BBox rb = new BBox(centre.lon() - dLon, centre.lat() - dLat, centre.lon() + dLon, centre.lat() + dLat);
				List<OsmPrimitive> c = new ArrayList<>();
				c.addAll(ds.searchNodes(rb));
				c.addAll(ds.searchWays(rb));
				c.addAll(ds.searchRelations(rb));
				candidates = c;
			}
		}
		BBox idBox = null;
		if (!ids.isEmpty() && bboxObj instanceof List && ((List<?>) bboxObj).size() == 4) {
			List<?> b = (List<?>) bboxObj;
			idBox = new BBox(toDouble(b.get(0), "bbox"), toDouble(b.get(1), "bbox"), toDouble(b.get(2), "bbox"), toDouble(b.get(3), "bbox"));
		}
		List<OsmPrimitive> results = new ArrayList<>();
		for (OsmPrimitive prim : candidates) {
			if (prim.isDeleted() || prim.isIncomplete() || (matcher != null && !matcher.match(prim))) {
				continue;
			}
			if (idBox != null && !idBox.intersects(prim.getBBox())) {
				continue;
			}
			if (ring != null && !JosmUtils.insidePolygon(prim, ring)) {
				continue;
			}
			if (centre != null && JosmUtils.distanceMetres(prim, centre) > radius) {
				continue;
			}
			results.add(prim);
		}

		List<Map<String, Object>> elements = new ArrayList<>();
		for (int i = offset, limit = Math.min(results.size(), offset + maxResults); i < limit; i++) {
			OsmPrimitive prim = results.get(i);
			Map<String, Object> m = JosmUtils.toMap(prim);
			if (includeGeometry && prim instanceof Way) {
				List<Map<String, Object>> geometry = new ArrayList<>();
				for (Node n : ((Way) prim).getNodes()) {
					Map<String, Object> nm = new LinkedHashMap<>();
					nm.put("id", n.getUniqueId());
					if (n.getCoor() != null) {
						nm.put("lat", n.getCoor().lat());
						nm.put("lon", n.getCoor().lon());
					}
					geometry.add(nm);
				}
				m.put("nodes", geometry);
			}
			if (fields != null) {
				m.keySet().retainAll(fields);
			}
			elements.add(m);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("query", query);
		if (bboxObj != null) {
			result.put("bbox", bboxObj);
		}
		if (ring != null) {
			result.put("polygon_vertices", ring.size());
		}
		if (centre != null) {
			result.put("center", centreObj);
			result.put("radius_m", radius);
		}
		result.put("total_matches", results.size());
		if (groupBy != null) {
			// Counting replaces the element list: the point is to keep the elements out of the result.
			Map<String, Integer> counts = new java.util.HashMap<>();
			int without = 0;
			for (OsmPrimitive p : results) {
				String v = p.get(groupBy);
				if (v == null) {
					without++;
				} else {
					counts.merge(v, 1, Integer::sum);
				}
			}
			List<Map<String, Object>> groups = new ArrayList<>();
			counts.entrySet().stream()
					.sorted((a, b) -> b.getValue().equals(a.getValue())
							? a.getKey().compareTo(b.getKey())
							: b.getValue() - a.getValue())
					.forEach(e -> {
						Map<String, Object> g = new LinkedHashMap<>();
						g.put("value", e.getKey());
						g.put("count", e.getValue());
						groups.add(g);
					});
			result.put("group_by", groupBy);
			result.put("distinct_values", groups.size());
			result.put("without_key", without);
			result.put("groups", groups);
			return JosmUtils.toJson(result);
		}
		result.put("offset", offset);
		result.put("returned", elements.size());
		result.put("truncated", offset + elements.size() < results.size());
		result.put("elements", elements);
		return JosmUtils.toJson(result);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	protected boolean supportsOutputPath() {
		return true;
	}
}
