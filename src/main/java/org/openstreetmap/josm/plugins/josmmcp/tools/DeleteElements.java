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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.utils.JosmUtils;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Deletes many objects in one call, one undo step and one confirmation dialog.
 */
public class DeleteElements extends BaseTool {

	static final int MAX_ELEMENTS = 5000;

	@Override
	public String getName() {
		return "delete_elements";
	}

	@Override
	public String getDescription() {
		return "Delete many objects at once ([{type, id}], up to " + MAX_ELEMENTS + ") as a single undo step, with one "
				+ "confirmation. Like JOSM's Delete: objects are removed from referencing ways and relations, and "
				+ "untagged nodes of deleted ways that no other way uses go too (delete_way_nodes=false keeps them). "
				+ "All objects must exist, otherwise nothing is deleted.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> el = new HashMap<>();
		el.put("type", "object");
		el.put("properties", Map.of("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")),
				"id", Map.of("type", "integer")));
		el.put("required", Arrays.asList("type", "id"));
		props.put("elements", Map.of("type", "array", "items", el, "minItems", 1, "maxItems", MAX_ELEMENTS));
		props.put("delete_way_nodes", Map.of("type", "boolean",
				"description", "Also delete untagged nodes of deleted ways that no other way uses (default true)"));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("elements"), null, null, null);
	}

	@Override
	public boolean returnsJson() {
		return true;
	}

	private static Set<OsmPrimitive> resolve(DataSet ds, Map<String, Object> args) throws Exception {
		Object elsObj = requireArg(args, "elements");
		if (!(elsObj instanceof List) || ((List<?>) elsObj).isEmpty() || ((List<?>) elsObj).size() > MAX_ELEMENTS) {
			throw new Exception("elements must be an array of 1 to " + MAX_ELEMENTS + " {type, id} objects");
		}
		Set<OsmPrimitive> prims = new LinkedHashSet<>();
		for (Object o : (List<?>) elsObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each element must be an object with type and id");
			}
			Map<?, ?> m = (Map<?, ?>) o;
			String type = String.valueOf(m.get("type"));
			long id = toLong(m.get("id"), "id");
			OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.from(type)));
			if (p == null || p.isDeleted()) {
				throw new Exception(type + " " + id + " not found; nothing was deleted");
			}
			prims.add(p);
		}
		return prims;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Set<OsmPrimitive> prims = resolve(ds, args);
		boolean wayNodes = !Boolean.FALSE.equals(args.get("delete_way_nodes"));
		Command c = DeleteCommand.delete(prims, wayNodes, true);
		if (c == null) {
			throw new Exception("the objects could not be deleted");
		}
		addCommand(c, tr("Delete {0} objects", prims.size()));

		Map<String, Integer> counts = new TreeMap<>();
		for (OsmPrimitive p : prims) {
			counts.merge(p.getType().getAPIName(), 1, Integer::sum);
		}
		int deleted = 0;
		for (OsmPrimitive p : c.getParticipatingPrimitives()) {
			if (p.isDeleted()) {
				deleted++;
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("requested", counts);
		result.put("deleted", deleted);
		return JosmUtils.toJson(result);
	}

	@Override
	public Category category() {
		return Category.DELETE;
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		try {
			DataSet ds = MainApplication.getLayerManager().getEditDataSet();
			if (ds == null) {
				return "Delete " + args;
			}
			Set<OsmPrimitive> prims = resolve(ds, args);
			Map<String, Integer> counts = new TreeMap<>();
			List<String> tagged = new ArrayList<>();
			for (OsmPrimitive p : prims) {
				counts.merge(p.getType().getAPIName(), 1, Integer::sum);
				if (p.hasKeys() && tagged.size() < 5) {
					tagged.add(p.getType().getAPIName() + " " + p.getUniqueId() + " " + p.getKeys());
				}
			}
			StringBuilder sb = new StringBuilder("Delete " + prims.size() + " objects: " + counts);
			if (!tagged.isEmpty()) {
				sb.append("; tagged among them: ").append(tagged);
			}
			return sb.toString();
		} catch (Exception e) {
			return "Delete " + args;
		}
	}
}
