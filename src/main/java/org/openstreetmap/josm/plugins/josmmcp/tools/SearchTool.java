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
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.search.SearchCompiler;
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
		queryProp.put("description", "JOSM search expression, e.g. 'highway=residential' or 'amenity=restaurant name:pizza'");
		searchProps.put("query", queryProp);
		Map<String, Object> maxResultsProp = new HashMap<>();
		maxResultsProp.put("type", "integer");
		maxResultsProp.put("description", "Maximum number of elements to return (default 50)");
		searchProps.put("max_results", maxResultsProp);
		searchProps.put("offset", Map.of("type", "integer", "description", "Skip this many matches first, for paging (default 0)"));
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
		searchProps.put("ids", Map.of("type", "array", "items", Map.of("type", "integer"), "maxItems", 5000,
				"description", "Only these element ids (any type); with ids the query is optional and bbox/polygon/center "
						+ "still filter, so 'which of these ids lie in this area' is one call"));
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
		int maxResults = getInt(args, "max_results", 50);
		if (maxResults < 0) {
			throw new Exception("max_results must not be negative");
		}

		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
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

		SearchCompiler.Match matcher = queryObj == null ? null : SearchCompiler.compile(query);
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
			Map<String, Object> m = JosmUtils.toMap(results.get(i));
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
