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
import java.util.TreeMap;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Summarises what would be uploaded: counts per type and state, the keys present on changed
 * objects, the area touched and the undo descriptions. Meant for drafting a changeset comment.
 */
public class PendingChangesSummary extends BaseTool {

	@Override
	public String getName() {
		return "pending_changes_summary";
	}

	@Override
	public String getDescription() {
		return "Summarise the pending changes of the active layer: counts of new, modified and deleted objects per type, "
				+ "the tag keys they carry, the bounding box of the changes and the undo history grouped by description. "
				+ "Use it to write a changeset comment. Does not change anything.";
	}

	@Override
	public JsonSchema getInputSchema() {
		return new McpSchema.JsonSchema("object", new HashMap<>(), null, null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Map<String, Integer> counts = new TreeMap<>();
		Map<String, Integer> keys = new TreeMap<>();
		double minLat = 90;
		double minLon = 180;
		double maxLat = -90;
		double maxLon = -180;
		int total = 0;
		for (OsmPrimitive p : ds.allPrimitives()) {
			if (!(p.isModified() || p.isNew() || p.isDeleted())) {
				continue;
			}
			total++;
			String state = p.isDeleted() ? "deleted" : p.isNew() ? "new" : "modified";
			counts.merge(p.getType().getAPIName() + "_" + state, 1, Integer::sum);
			if (!p.isDeleted()) {
				for (String k : p.keySet()) {
					keys.merge(k, 1, Integer::sum);
				}
			}
			List<Node> nodes = new ArrayList<>();
			if (p instanceof Node) {
				nodes.add((Node) p);
			} else if (p instanceof Way) {
				nodes.addAll(((Way) p).getNodes());
			}
			for (Node n : nodes) {
				if (n.getCoor() == null) {
					continue;
				}
				minLat = Math.min(minLat, n.lat());
				maxLat = Math.max(maxLat, n.lat());
				minLon = Math.min(minLon, n.lon());
				maxLon = Math.max(maxLon, n.lon());
			}
		}
		Map<String, Integer> undo = new LinkedHashMap<>();
		for (Command c : UndoRedoHandler.getInstance().getUndoCommands()) {
			undo.merge(c.getDescriptionText(), 1, Integer::sum);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("layer", MainApplication.getLayerManager().getEditLayer().getName());
		result.put("total_changed_objects", total);
		result.put("counts", counts);
		result.put("tag_keys_on_changed_objects", keys);
		if (total > 0 && minLat <= maxLat) {
			Map<String, Object> bbox = new LinkedHashMap<>();
			bbox.put("min_lat", minLat);
			bbox.put("min_lon", minLon);
			bbox.put("max_lat", maxLat);
			bbox.put("max_lon", maxLon);
			result.put("bbox", bbox);
		}
		result.put("undo_history", undo);
		return JosmUtils.toJson(result);
	}
}
