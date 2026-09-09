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
import org.openstreetmap.josm.io.OverpassDownloadReader;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Runs an Overpass query through JOSM's own Overpass downloader and merges the result into a
 * layer, so objects outside the downloaded area (all bus stops of a municipality, every
 * building with a given ref) can be fetched without touching the map by hand.
 */
public class DownloadOverpass extends BaseTool {

	@Override
	public String getName() {
		return "download_overpass";
	}

	@Override
	public String getDescription() {
		return "Run an Overpass QL query with JOSM's Overpass downloader and merge the result into the active layer (or a "
				+ "new one). Use {{bbox}} in the query for the given bbox; JOSM also expands {{geocodeArea:...}} and the "
				+ "other JOSM Overpass extensions. Keep queries small and always end them with 'out meta;' (or 'out meta geom;'), "
				+ "otherwise JOSM cannot load the result. Waits for the download.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("query", Map.of("type", "string", "description", "Overpass QL, e.g. node[\"highway\"=\"bus_stop\"]({{bbox}}); out meta;"));
		props.put("bbox", Map.of("type", "array", "items", Map.of("type", "number"), "minItems", 4, "maxItems", 4,
				"description", "[min_lon, min_lat, max_lon, max_lat] substituted for {{bbox}}; default: the layer's downloaded bounds"));
		props.put("new_layer", Map.of("type", "boolean", "description", "Load into a new layer (default false)"));
		props.put("layer_name", Map.of("type", "string"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("query"), null, null, null);
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
		String query = requireArg(args, "query").toString().trim();
		if (query.isEmpty()) {
			throw new Exception("query must not be empty");
		}
		if (!query.contains("out")) {
			throw new Exception("the query needs an output statement such as 'out meta;'");
		}
		Bounds bounds;
		Object bboxObj = args.get("bbox");
		if (bboxObj != null) {
			if (!(bboxObj instanceof List) || ((List<?>) bboxObj).size() != 4) {
				throw new Exception("bbox must be [min_lon, min_lat, max_lon, max_lat]");
			}
			List<?> b = (List<?>) bboxObj;
			bounds = new Bounds(toDouble(b.get(1), "bbox"), toDouble(b.get(0), "bbox"), toDouble(b.get(3), "bbox"), toDouble(b.get(2), "bbox"));
		} else {
			bounds = runInEDT(() -> {
				DataSet ds = MainApplication.getLayerManager().getEditDataSet();
				if (ds == null || ds.getDataSourceBounds().isEmpty()) {
					return null;
				}
				Bounds u = new Bounds(ds.getDataSourceBounds().get(0));
				for (Bounds bb : ds.getDataSourceBounds()) {
					u.extend(bb);
				}
				return u;
			});
			if (bounds == null) {
				throw new Exception("no downloaded bounds to use for {{bbox}}; pass 'bbox'");
			}
		}
		if (!bounds.isValid()) {
			throw new Exception("bbox is not valid");
		}
		boolean newLayer = Boolean.TRUE.equals(args.get("new_layer"));
		if (!newLayer && runInEDT(() -> MainApplication.getLayerManager().getEditLayer()) == null) {
			newLayer = true;
		}
		DownloadParams params = new DownloadParams().withNewLayer(newLayer);
		Object nameObj = args.get("layer_name");
		if (nameObj != null && !nameObj.toString().isBlank()) {
			params = params.withLayerName(nameObj.toString());
		}
		String server = OverpassDownloadReader.OVERPASS_SERVER.get();
		OverpassDownloadReader reader = new OverpassDownloadReader(bounds, server, query);
		DownloadOsmTask task = new DownloadOsmTask();
		final DownloadParams p = params;
		final Bounds bnds = bounds;
		Future<?> future = runInEDT(() -> task.download(reader, p, bnds, null));
		if (future != null) {
			future.get(10, TimeUnit.MINUTES);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("server", server);
		out.put("failed", task.isFailed());
		out.put("canceled", task.isCanceled());
		if (!task.getErrorObjects().isEmpty()) {
			List<String> errs = new java.util.ArrayList<>();
			for (Object o : task.getErrorObjects()) {
				errs.add(String.valueOf(o instanceof Exception ? ((Exception) o).getMessage() : o));
			}
			out.put("errors", errs);
		}
		DataSet downloaded = task.getDownloadedData();
		if (downloaded != null) {
			out.put("downloaded_nodes", downloaded.getNodes().size());
			out.put("downloaded_ways", downloaded.getWays().size());
			out.put("downloaded_relations", downloaded.getRelations().size());
		}
		out.put("layer", runInEDT(() -> MainApplication.getLayerManager().getEditLayer() == null ? null
				: MainApplication.getLayerManager().getEditLayer().getName()));
		return Arrays.asList(new TextContent(JosmUtils.toJson(out)));
	}
}
