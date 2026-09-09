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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class StateTool extends BaseTool {

	@Override
	public String getName() {
		return "get_josm_state";
	}

	@Override
	public String getDescription() {
		return "Get the current state of JOSM: version, loaded layers and statistics about the active data layer";
	}

	@Override
	public JsonSchema getInputSchema() {
		return new McpSchema.JsonSchema("object", new HashMap<>(), null, null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("josm_version", Version.getInstance().getVersionString());

		OsmDataLayer editLayer = MainApplication.getLayerManager().getEditLayer();
		List<Map<String, Object>> layers = new ArrayList<>();
		for (Layer layer : MainApplication.getLayerManager().getLayers()) {
			Map<String, Object> l = new LinkedHashMap<>();
			l.put("name", layer.getName());
			l.put("type", layer.getClass().getSimpleName());
			l.put("active", layer == editLayer);
			l.put("visible", layer.isVisible());
			layers.add(l);
		}
		state.put("layers", layers);

		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			state.put("edit_dataset", null);
		} else {
			Map<String, Object> data = new LinkedHashMap<>();
			// Only count fully downloaded objects; JOSM also holds incomplete stubs of parents.
			int nodes = 0;
			int ways = 0;
			int relations = 0;
			int incomplete = 0;
			for (OsmPrimitive p : ds.allNonDeletedPrimitives()) {
				if (p.isIncomplete()) {
					incomplete++;
					continue;
				}
				switch (p.getType()) {
				case NODE: nodes++; break;
				case WAY: ways++; break;
				case RELATION: relations++; break;
				default: break;
				}
			}
			data.put("nodes", nodes);
			data.put("ways", ways);
			data.put("relations", relations);
			data.put("incomplete_stubs", incomplete);
			data.put("selected", ds.getAllSelected().size());
			data.put("modified", ds.isModified());
			List<Bounds> all = ds.getDataSourceBounds();
			Map<String, Object> bounds = new LinkedHashMap<>();
			bounds.put("count", all.size());
			if (!all.isEmpty()) {
				Bounds union = new Bounds(all.get(0));
				for (Bounds b : all) {
					union.extend(b);
				}
				Map<String, Object> u = new LinkedHashMap<>();
				u.put("min_lat", union.getMinLat());
				u.put("min_lon", union.getMinLon());
				u.put("max_lat", union.getMaxLat());
				u.put("max_lon", union.getMaxLon());
				bounds.put("union", u);
			}
			data.put("downloaded_bounds", bounds);
			state.put("edit_dataset", data);
		}

		return JosmUtils.toJson(state);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
