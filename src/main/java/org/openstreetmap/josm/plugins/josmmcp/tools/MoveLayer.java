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
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Moves a layer up or down the stack, so that an overlay can be put above the imagery
 * that was covering it. Index 0 is the top of the stack, the layer drawn over the rest.
 */
public class MoveLayer extends BaseTool {

	@Override
	public String getName() {
		return "move_layer";
	}

	@Override
	public String getDescription() {
		return "Move a JOSM layer up or down the layer stack, like dragging it in the layer list. "
				+ "Index 0 is the top of the stack, drawn over everything below it, so an overlay only shows "
				+ "when it sits above the imagery it belongs with. Give exactly one of 'position', 'direction', "
				+ "'above' or 'below'. Returns the resulting layer list with each layer's index.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("layer", Map.of("type", "string", "description",
				"Layer to move: exact name as listed by get_josm_state, or a unique case-insensitive substring"));
		props.put("position", Map.of("type", "integer", "description",
				"Absolute target index; 0 puts the layer on top, the layer count minus one at the bottom. "
						+ "Out-of-range values are clamped"));
		props.put("direction", Map.of("type", "string", "enum", Arrays.asList("up", "down", "top", "bottom"),
				"description", "Move one step up or down, or all the way to the top or bottom"));
		props.put("above", Map.of("type", "string", "description",
				"Name of another layer to place this one directly above (so it is drawn over that layer)"));
		props.put("below", Map.of("type", "string", "description",
				"Name of another layer to place this one directly below"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("layer"), null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		String wanted = requireArg(args, "layer").toString();
		List<Layer> layers = MainApplication.getLayerManager().getLayers();
		Layer target = JosmUtils.findLayer(layers, wanted);
		int from = layers.indexOf(target);

		List<Layer> others = new ArrayList<>(layers);
		others.remove(target);
		int last = others.size();

		List<String> given = new ArrayList<>();
		for (String k : Arrays.asList("position", "direction", "above", "below")) {
			if (args.get(k) != null) {
				given.add(k);
			}
		}
		if (given.isEmpty()) {
			throw new Exception("give one of position, direction, above or below");
		}
		if (given.size() > 1) {
			throw new Exception("give only one of position, direction, above or below, got " + given);
		}

		int to;
		String how = given.get(0);
		switch (how) {
		case "position":
			to = clamp(getInt(args, "position", 0), last);
			break;
		case "direction":
			String dir = args.get("direction").toString().toLowerCase(Locale.ROOT);
			switch (dir) {
			case "up":
				to = clamp(from - 1, last);
				break;
			case "down":
				to = clamp(from + 1, last);
				break;
			case "top":
				to = 0;
				break;
			case "bottom":
				to = last;
				break;
			default:
				throw new Exception("direction must be up, down, top or bottom, got '" + dir + "'");
			}
			break;
		case "above":
		case "below":
			Layer other = JosmUtils.findLayer(layers, args.get(how).toString());
			if (other == target) {
				throw new Exception("'" + how + "' names the layer being moved");
			}
			int ref = others.indexOf(other);
			to = "above".equals(how) ? ref : ref + 1;
			break;
		default:
			throw new Exception("unknown option " + how);
		}

		if (to != from) {
			// JOSM removes the layer and re-inserts it at this index, so 'to' is the resulting position.
			MainApplication.getLayerManager().moveLayer(target, to);
		}

		List<Layer> now = MainApplication.getLayerManager().getLayers();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("moved", target.getName());
		result.put("from_index", from);
		result.put("to_index", now.indexOf(target));
		result.put("unchanged", to == from);
		result.put("layers", JosmUtils.describeLayers(now));
		return JosmUtils.toJson(result);
	}

	private static int clamp(int v, int max) {
		return Math.max(0, Math.min(v, max));
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
