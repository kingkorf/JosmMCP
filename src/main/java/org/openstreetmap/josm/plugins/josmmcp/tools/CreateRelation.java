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
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class CreateRelation extends BaseTool {

	@Override
	public String getName() {
		return "create_relation";
	}

	@Override
	public String getDescription() {
		return "Create a new relation with the given tags and ordered members ({type, ref, role}). Returns the new id. "
				+ "All members must exist in the dataset.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		props.put("tags", Map.of("type", "object", "additionalProperties", Map.of("type", "string", "maxLength", 255),
				"description", "Tags, must include 'type' (e.g. multipolygon, route)"));
		Map<String, Object> member = new HashMap<>();
		member.put("type", "object");
		member.put("properties", Map.of("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")),
				"ref", Map.of("type", "integer"), "role", Map.of("type", "string")));
		member.put("required", Arrays.asList("type", "ref"));
		props.put("members", Map.of("type", "array", "items", member, "minItems", 1));
		return new McpSchema.JsonSchema("object", props, Arrays.asList("tags", "members"), null, null, null);
	}


	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		Object tagsObj = requireArg(args, "tags");
		if (!(tagsObj instanceof Map) || !((Map<?, ?>) tagsObj).containsKey("type")) {
			throw new Exception("tags must be an object containing a 'type' tag");
		}
		Object membersObj = requireArg(args, "members");
		if (!(membersObj instanceof List) || ((List<?>) membersObj).isEmpty()) {
			throw new Exception("members must be a non-empty array");
		}
		Relation r = new Relation();
		for (Map.Entry<?, ?> e : ((Map<?, ?>) tagsObj).entrySet()) {
			if (e.getValue() != null && !e.getValue().toString().isEmpty()) {
				r.put(String.valueOf(e.getKey()), e.getValue().toString());
			}
		}
		List<RelationMember> members = new ArrayList<>();
		for (Object o : (List<?>) membersObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each member must be an object with type, ref and optional role");
			}
			Map<?, ?> m = (Map<?, ?>) o;
			long ref = toLong(m.get("ref"), "ref");
			OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(ref, OsmPrimitiveType.from(String.valueOf(m.get("type")))));
			if (p == null) {
				throw new Exception(m.get("type") + " " + ref + " not found in dataset");
			}
			Object role = m.get("role");
			members.add(new RelationMember(role == null ? "" : role.toString(), p));
		}
		r.setMembers(members);
		addCommand(new AddCommand(ds, r), tr("Create relation {0}", r.getUniqueId()));
		return Long.toString(r.getUniqueId());
	}

	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
