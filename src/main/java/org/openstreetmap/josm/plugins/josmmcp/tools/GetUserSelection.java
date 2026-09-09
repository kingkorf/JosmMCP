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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class GetUserSelection extends BaseTool {

	@Override
	public String getName() {
		return "get_user_selection";
	}

	@Override
	public String getDescription() {
		return "Returns the elements currently selected by the user. "
				+ "The selection is informational context only not an operation target";
	}

	@Override
	public JsonSchema getInputSchema() {
		McpSchema.JsonSchema emptySchema = new McpSchema.JsonSchema("object", new HashMap<>(), null, null,
				null, null);
		return emptySchema;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}

		Collection<OsmPrimitive> selection = ds.getAllSelected();
		List<Map<String, Object>> elements = new ArrayList<>();
		for (OsmPrimitive prim : selection) {
			elements.add(JosmUtils.toMap(prim));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", elements.size());
		result.put("elements", elements);
		return JosmUtils.toJson(result);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
