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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class ModifyTags extends BaseTool {

	@Override
	public String getName() {
		return "modify_tags";
	}

	@Override
	public String getDescription() {
		return "Modify tags on an OSM element. To remove a tag use a null or empty value";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> modifyProps = new HashMap<>();
		Map<String, Object> elTypeProp = new HashMap<>();
		elTypeProp.put("type", "string");
		elTypeProp.put("enum", new ArrayList<String>(Arrays.asList("node", "way", "relation")));
		modifyProps.put("element_type", elTypeProp);
		Map<String, Object> elementIdProp = new HashMap<>();
		elementIdProp.put("type", "integer");
		modifyProps.put("element_id", elementIdProp);

		Map<String, Object> tagsProp = new HashMap<>();
		tagsProp.put("type", "object");
		tagsProp.put("description", "Map of tag key to value; a null or empty value removes the tag");
		// Every value must be a string of at most 255 characters (the OSM API limit).
		tagsProp.put("additionalProperties", Map.of("type", "string", "maxLength", 255));
		modifyProps.put("tags", tagsProp);
		McpSchema.JsonSchema modifySchema = new McpSchema.JsonSchema("object", modifyProps,
				Arrays.asList("element_type", "element_id", "tags"), null, null, null);
		return modifySchema;
	}

	@Override
	protected boolean requiresConfirmation(Map<String, Object> args) throws Exception {
		if (args == null || !"relation".equals(String.valueOf(args.get("element_type")))) {
			return false;
		}
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			return false;
		}
		OsmPrimitive r = ds.getPrimitiveById(new SimplePrimitiveId(toLong(args.get("element_id"), "element_id"), OsmPrimitiveType.RELATION));
		return r instanceof org.openstreetmap.josm.data.osm.Relation
				&& ((org.openstreetmap.josm.data.osm.Relation) r).getMembersCount() > ModifyTagsBatch.CONFIRM_RELATION_MEMBERS;
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		return "Change tags " + args.get("tags") + " on relation " + args.get("element_id") + ", which has more than "
				+ ModifyTagsBatch.CONFIRM_RELATION_MEMBERS + " members";
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}

		String type = (String) args.get("element_type");
		long id = getLong(args, "element_id");
		OsmPrimitive el = ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.from(type)));
		if (el == null) {
			throw new Exception(type + " with id " + id + " not found");
		}

		Object tagsObj = requireArg(args, "tags");
		if (!(tagsObj instanceof Map)) {
			throw new Exception("tags must be a map/object");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> tagsMap = (Map<String, Object>) tagsObj;
		if (tagsMap.isEmpty()) {
			throw new Exception("tags must not be empty");
		}

		Collection<Command> cmds = new LinkedList<>();
		for (Map.Entry<String, Object> entry : tagsMap.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue() == null ? "" : entry.getValue().toString();
			cmds.add(new ChangePropertyCommand(el, key, value));
		}

		Command c = new SequenceCommand(tr("Modify tags of {0} {1} (MCP)", type, id), cmds, false);
		addCommand(c, c.getDescriptionText());
		return "Tags of " + type + " " + id + " updated";
	}


	@Override
	public Category category() {
		return Category.TAGS;
	}
}
