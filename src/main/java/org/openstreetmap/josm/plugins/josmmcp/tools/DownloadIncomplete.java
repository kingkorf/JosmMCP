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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.io.DownloadPrimitivesTask;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Completes incomplete objects: either the members of given relations, or the incomplete
 * stubs in the layer (capped, because a layer can hold thousands of them).
 */
public class DownloadIncomplete extends BaseTool {

	@Override
	public String getName() {
		return "download_incomplete";
	}

	@Override
	public String getDescription() {
		return "Download incomplete objects from the OSM server. Pass 'relations' to fully download those relations "
				+ "and their members, or nothing to complete up to 'limit' (default 200) incomplete stubs in the layer. "
				+ "Waits for the download to finish.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("relations", Map.of("type", "array", "items", Map.of("type", "integer"), "description", "Relation ids to complete"));
		props.put("limit", Map.of("type", "integer", "description", "Maximum number of incomplete stubs to fetch when no relations are given (default 200, max 2000)"));
		return new McpSchema.JsonSchema("object", props, null, null, null, null);
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
		OsmDataLayer layer = runInEDT(() -> MainApplication.getLayerManager().getEditLayer());
		if (layer == null) {
			throw new Exception("no active data layer");
		}
		DataSet ds = layer.getDataSet();
		int limit = Math.min(2000, getInt(args, "limit", 200));
		Object relObj = args == null ? null : args.get("relations");
		List<PrimitiveId> ids = new ArrayList<>();
		boolean full = false;
		if (relObj instanceof List && !((List<?>) relObj).isEmpty()) {
			for (Object o : (List<?>) relObj) {
				long id = toLong(o, "relations");
				OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.RELATION));
				if (p == null) {
					throw new Exception("relation " + id + " not in dataset");
				}
				ids.add(p.getPrimitiveId());
			}
			full = true;
		} else {
			runInEDT(() -> {
				for (OsmPrimitive p : ds.allPrimitives()) {
					if (p.isIncomplete() && !(p instanceof Relation) && ids.size() < limit) {
						ids.add(p.getPrimitiveId());
					}
				}
				if (ids.size() < limit) {
					for (OsmPrimitive p : ds.allPrimitives()) {
						if (p.isIncomplete() && p instanceof Relation && ids.size() < limit) {
							ids.add(p.getPrimitiveId());
						}
					}
				}
				return null;
			});
		}
		if (ids.isEmpty()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("requested", 0);
			r.put("message", "nothing to download");
			return Arrays.asList(new TextContent(JosmUtils.toJson(r)));
		}
		DownloadPrimitivesTask task = new DownloadPrimitivesTask(layer, ids, full, NullProgressMonitor.INSTANCE);
		task.run(); // runs the download on this (non-EDT) thread and applies the result on the EDT
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("requested", ids.size());
		r.put("full_relations", full);
		r.put("still_incomplete", runInEDT(() -> {
			int n = 0;
			for (OsmPrimitive p : ds.allPrimitives()) {
				if (p.isIncomplete()) {
					n++;
				}
			}
			return n;
		}));
		return Arrays.asList(new TextContent(JosmUtils.toJson(r)));
	}
}
