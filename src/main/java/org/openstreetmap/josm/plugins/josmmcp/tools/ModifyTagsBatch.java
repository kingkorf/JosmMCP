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

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Applies tag changes to many elements as one undoable command.
 */
public class ModifyTagsBatch extends BaseTool {

	@Override
	public String getName() {
		return "modify_tags_batch";
	}

	@Override
	public String getDescription() {
		return "Modify tags on many elements at once, as a single undo step. Each change names an element and a tag map; "
				+ "a null or empty value removes the tag. All elements must exist, otherwise nothing is changed. "
				+ "Prefer this over repeated modify_tags calls for more than a handful of elements.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> change = new HashMap<>();
		change.put("type", "object");
		change.put("properties", Map.of(
				"element_type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")),
				"element_id", Map.of("type", "integer"),
				"tags", Map.of("type", "object", "additionalProperties", Map.of("type", "string", "maxLength", 255))));
		change.put("required", Arrays.asList("element_type", "element_id", "tags"));
		props.put("changes", Map.of("type", "array", "items", change, "minItems", 1));
		props.put("description", Map.of("type", "string", "description", "Short text for the undo history, e.g. 'Sync start_date with BAG'"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("changes"), null, null, null);
	}

	@Override
	public boolean isWriteTool() {
		return true;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object changesObj = requireArg(args, "changes");
		if (!(changesObj instanceof List) || ((List<?>) changesObj).isEmpty()) {
			throw new Exception("changes must be a non-empty array");
		}
		List<Command> cmds = new ArrayList<>();
		int elements = 0;
		int tagOps = 0;
		for (Object o : (List<?>) changesObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each change must be an object");
			}
			Map<?, ?> c = (Map<?, ?>) o;
			String type = String.valueOf(c.get("element_type"));
			long id = toLong(c.get("element_id"), "element_id");
			OsmPrimitive el = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.from(type)));
			if (el == null) {
				throw new Exception(type + " " + id + " not found; nothing was changed");
			}
			Object tagsObj = c.get("tags");
			if (!(tagsObj instanceof Map) || ((Map<?, ?>) tagsObj).isEmpty()) {
				throw new Exception("tags for " + type + " " + id + " must be a non-empty object");
			}
			elements++;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) tagsObj).entrySet()) {
				String value = e.getValue() == null ? "" : e.getValue().toString();
				cmds.add(new ChangePropertyCommand(el, String.valueOf(e.getKey()), value));
				tagOps++;
			}
		}
		Object descObj = args.get("description");
		String desc = descObj == null || descObj.toString().isBlank()
				? tr("Modify tags of {0} elements (MCP)", elements) : descObj.toString() + " (MCP)";
		UndoRedoHandler.getInstance().add(new SequenceCommand(desc, cmds, false));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("elements", elements);
		result.put("tag_changes", tagOps);
		result.put("undo_description", desc);
		return JosmUtils.toJson(result);
	}
}
