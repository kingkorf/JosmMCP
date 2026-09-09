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
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

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
		searchProps.put("fields", Map.of("type", "array", "items", Map.of("type", "string"),
				"description", "Only include these fields per element, e.g. [\"id\",\"type\",\"tags\"]; omit node_ids/members to keep results small"));
		McpSchema.JsonSchema searchSchema = new McpSchema.JsonSchema("object", searchProps, Arrays.asList("query"),
				null, null, null);
		return searchSchema;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		String query = requireArg(args, "query").toString();
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

		SearchCompiler.Match matcher = SearchCompiler.compile(query);
		Collection<OsmPrimitive> candidates;
		Object bboxObj = args.get("bbox");
		if (bboxObj != null) {
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
		List<OsmPrimitive> results = new ArrayList<>();
		for (OsmPrimitive prim : candidates) {
			if (!prim.isDeleted() && !prim.isIncomplete() && matcher.match(prim)) {
				results.add(prim);
			}
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
		result.put("total_matches", results.size());
		result.put("offset", offset);
		result.put("returned", elements.size());
		result.put("truncated", offset + elements.size() < results.size());
		result.put("elements", elements);
		return JosmUtils.toJson(result);
	}
}
