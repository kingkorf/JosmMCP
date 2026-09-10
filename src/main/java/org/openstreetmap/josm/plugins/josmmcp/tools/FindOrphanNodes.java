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

import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Lists untagged nodes that no way or relation uses: leftovers of geometry edits that only clutter the
 * upload. Feed the ids to delete_elements.
 */
public class FindOrphanNodes extends BaseTool {

	@Override
	public String getName() {
		return "find_orphan_nodes";
	}

	@Override
	public String getDescription() {
		return "List untagged nodes that belong to no way or relation, in a bbox or the whole layer; typically "
				+ "leftovers of geometry edits. Pass the ids to delete_elements to clean up. With modified_only=true "
				+ "(default) only new or modified nodes are reported, so untouched server data is not flagged.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("bbox", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 4, "maxItems", 4,
				"description", "[min_lon, min_lat, max_lon, max_lat]; default: the whole layer"));
		props.put("modified_only", Map.of("type", "boolean",
				"description", "Only nodes that are new or modified in this layer (default true)"));
		props.put("max_results", Map.of("type", "integer", "description", "Maximum number of nodes to return (default 500)"));
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
		boolean modifiedOnly = !(args != null && Boolean.FALSE.equals(args.get("modified_only")));
		int maxResults = getInt(args, "max_results", 500);
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
		List<Map<String, Object>> out = new ArrayList<>();
		List<Long> ids = new ArrayList<>();
		int total = 0;
		for (Node n : candidates) {
			if (n.isDeleted() || n.isIncomplete() || n.isTagged() || !n.getReferrers().isEmpty()) {
				continue;
			}
			if (modifiedOnly && !(n.isNew() || n.isModified())) {
				continue;
			}
			total++;
			if (out.size() < maxResults) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", n.getUniqueId());
				if (n.getCoor() != null) {
					m.put("lat", n.lat());
					m.put("lon", n.lon());
				}
				out.add(m);
				ids.add(n.getUniqueId());
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("total", total);
		result.put("returned", out.size());
		result.put("truncated", out.size() < total);
		result.put("ids", ids);
		result.put("nodes", out);
		return JosmUtils.toJson(result);
	}
}
