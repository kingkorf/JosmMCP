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

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.openstreetmap.josm.actions.downloadtasks.DownloadOsmTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Downloads OSM data for a bounding box into the active layer (or a new one), like File &gt; Download.
 */
public class DownloadArea extends BaseTool {
	/** The OSM API refuses larger areas. */
	private static final double MAX_AREA_DEG2 = 0.25;

	@Override
	public String getName() {
		return "download_area";
	}

	@Override
	public String getDescription() {
		return "Download OSM data for [min_lon, min_lat, max_lon, max_lat] from the OSM server into the active data layer "
				+ "(or a new layer with new_layer=true), like File > Download. The area must be under 0.25 square degrees "
				+ "(the API limit); keep it small, a village is plenty. Waits for the download to finish.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("bbox", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 4, "maxItems", 4,
				"description", "[min_lon, min_lat, max_lon, max_lat]"));
		props.put("new_layer", Map.of("type", "boolean", "description", "Download into a new layer (default false)"));
		props.put("layer_name", Map.of("type", "string", "description", "Name for the new layer"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("bbox"), null, null, null);
	}

	@Override
	public Category category() {
		return Category.DOWNLOAD;
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		throw new UnsupportedOperationException("use execute()");
	}

	@Override
	protected List<Content> execute(Map<String, Object> args) throws Exception {
		Object bboxObj = requireArg(args, "bbox");
		if (!(bboxObj instanceof List) || ((List<?>) bboxObj).size() != 4) {
			throw new Exception("bbox must be [min_lon, min_lat, max_lon, max_lat]");
		}
		List<?> b = (List<?>) bboxObj;
		double minLon = toDouble(b.get(0), "bbox");
		double minLat = toDouble(b.get(1), "bbox");
		double maxLon = toDouble(b.get(2), "bbox");
		double maxLat = toDouble(b.get(3), "bbox");
		if (minLat >= maxLat || minLon >= maxLon) {
			throw new Exception("bbox min values must be smaller than max values");
		}
		Bounds bounds = new Bounds(minLat, minLon, maxLat, maxLon);
		if (!bounds.isValid()) {
			throw new Exception("bbox is not valid");
		}
		if (bounds.getArea() > MAX_AREA_DEG2) {
			throw new Exception(String.format(java.util.Locale.ROOT,
					"area is %.3f square degrees; the OSM API allows at most %.2f. Split the request.", bounds.getArea(), MAX_AREA_DEG2));
		}
		boolean newLayer = Boolean.TRUE.equals(args.get("new_layer"));
		Object nameObj = args.get("layer_name");
		if (!newLayer && MainApplication.getLayerManager().getEditLayer() == null) {
			newLayer = true;
		}
		DownloadParams params = new DownloadParams().withNewLayer(newLayer);
		if (nameObj != null && !nameObj.toString().isBlank()) {
			params = params.withLayerName(nameObj.toString());
		}
		DownloadOsmTask task = new DownloadOsmTask();
		final DownloadParams p = params;
		Future<?> future = runInEDT(() -> task.download(p, bounds, null));
		if (future != null) {
			future.get(10, TimeUnit.MINUTES);
		}
		Map<String, Object> result = runInEDT(() -> {
			Map<String, Object> r = new LinkedHashMap<>();
			DataSet ds = MainApplication.getLayerManager().getEditDataSet();
			r.put("layer", MainApplication.getLayerManager().getEditLayer() == null ? null
					: MainApplication.getLayerManager().getEditLayer().getName());
			if (ds != null) {
				int nodes = 0;
				int ways = 0;
				int relations = 0;
				for (org.openstreetmap.josm.data.osm.OsmPrimitive prim : ds.allNonDeletedPrimitives()) {
					if (prim.isIncomplete()) {
						continue;
					}
					switch (prim.getType()) {
					case NODE: nodes++; break;
					case WAY: ways++; break;
					case RELATION: relations++; break;
					default: break;
					}
				}
				r.put("nodes", nodes);
				r.put("ways", ways);
				r.put("relations", relations);
				r.put("downloaded_bounds", ds.getDataSourceBounds().size());
			}
			return r;
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("downloaded", Map.of("min_lat", minLat, "min_lon", minLon, "max_lat", maxLat, "max_lon", maxLon));
		out.put("failed", task.isFailed());
		out.put("canceled", task.isCanceled());
		if (!task.getErrorObjects().isEmpty()) {
			List<String> errs = new java.util.ArrayList<>();
			for (Object o : task.getErrorObjects()) {
				errs.add(String.valueOf(o instanceof Exception ? ((Exception) o).getMessage() : o));
			}
			out.put("errors", errs);
		}
		out.putAll(result);
		return Arrays.asList(new TextContent(JosmUtils.toJson(out)));
	}
}
