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

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Shows or hides a layer, so that e.g. imagery can be inspected without an opaque
 * overlay, or the active layer can be switched. Layer names come from get_josm_state.
 */
public class SetLayerVisibility extends BaseTool {

	@Override
	public String getName() {
		return "set_layer_visibility";
	}

	@Override
	public String getDescription() {
		return "Show or hide a JOSM layer by name (as listed by get_josm_state), and optionally change its opacity. "
				+ "Useful to hide an opaque overlay before capture_map_view. Returns the resulting layer list.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> name = new HashMap<>();
		name.put("type", "string");
		name.put("description", "Exact layer name, or a unique case-insensitive substring of it");
		props.put("layer", name);
		Map<String, Object> visible = new HashMap<>();
		visible.put("type", "boolean");
		props.put("visible", visible);
		Map<String, Object> opacity = new HashMap<>();
		opacity.put("type", "number");
		opacity.put("description", "Opacity between 0.0 and 1.0 (optional)");
		props.put("opacity", opacity);
		return new McpSchema.JsonSchema("object", props, Arrays.asList("layer"), null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		String wanted = requireArg(args, "layer").toString();
		List<Layer> layers = MainApplication.getLayerManager().getLayers();
		Layer target = JosmUtils.findLayer(layers, wanted);

		Object vis = args.get("visible");
		if (vis != null) {
			if (!(vis instanceof Boolean)) {
				throw new Exception("visible must be a boolean");
			}
			target.setVisible((Boolean) vis);
		}
		Object op = args.get("opacity");
		if (op != null) {
			double o = toDouble(op, "opacity");
			if (o < 0 || o > 1) {
				throw new Exception("opacity must be between 0 and 1");
			}
			target.setOpacity(o);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("changed", target.getName());
		result.put("layers", JosmUtils.describeLayers(layers));
		return JosmUtils.toJson(result);
	}

	@Override
	public Category category() {
		return Category.VIEW;
	}

	@Override
	public boolean returnsJson() {
		return true;
	}
}
