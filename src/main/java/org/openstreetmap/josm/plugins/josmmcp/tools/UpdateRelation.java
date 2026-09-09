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
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.gui.MainApplication;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Replaces the member list of a relation (order, roles, membership). Tags are left
 * untouched; use modify_tags for those.
 */
public class UpdateRelation extends BaseTool {

	@Override
	public String getName() {
		return "update_relation_members";
	}

	@Override
	public String getDescription() {
		return "Replace the complete member list of a relation, in the given order. Use read_relation first, "
				+ "then send the full corrected list. All members must exist in the current dataset. Tags are not changed.";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> props = new HashMap<>();
		Map<String, Object> id = new HashMap<>();
		id.put("type", "integer");
		props.put("id", id);
		Map<String, Object> member = new HashMap<>();
		member.put("type", "object");
		Map<String, Object> mprops = new HashMap<>();
		mprops.put("type", Map.of("type", "string", "enum", Arrays.asList("node", "way", "relation")));
		mprops.put("ref", Map.of("type", "integer"));
		mprops.put("role", Map.of("type", "string"));
		member.put("properties", mprops);
		member.put("required", Arrays.asList("type", "ref"));
		Map<String, Object> members = new HashMap<>();
		members.put("type", "array");
		members.put("items", member);
		members.put("description", "Full ordered member list: [{type, ref, role}]. An omitted role means empty role.");
		props.put("members", members);
		return new McpSchema.JsonSchema("object", props, Arrays.asList("id", "members"), null, null, null);
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}
		long id = getLong(args, "id");
		Relation rel = (Relation) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.RELATION));
		if (rel == null) {
			throw new Exception("Relation with id " + id + " not found");
		}
		if (rel.isIncomplete()) {
			throw new Exception("Relation " + id + " is not fully downloaded");
		}
		Object membersObj = requireArg(args, "members");
		if (!(membersObj instanceof List) || ((List<?>) membersObj).isEmpty()) {
			throw new Exception("members must be a non-empty array");
		}
		List<RelationMember> members = new ArrayList<>();
		for (Object o : (List<?>) membersObj) {
			if (!(o instanceof Map)) {
				throw new Exception("each member must be an object with type, ref and optional role");
			}
			Map<?, ?> m = (Map<?, ?>) o;
			Object type = m.get("type");
			Object ref = m.get("ref");
			if (type == null || ref == null) {
				throw new Exception("member needs type and ref");
			}
			long refId = toLong(ref, "ref");
			OsmPrimitive p = ds.getPrimitiveById(new SimplePrimitiveId(refId, OsmPrimitiveType.from(type.toString())));
			if (p == null) {
				throw new Exception(type + " " + refId + " not found in dataset");
			}
			if (p.equals(rel)) {
				throw new Exception("a relation cannot be a member of itself");
			}
			Object role = m.get("role");
			members.add(new RelationMember(role == null ? "" : role.toString(), p));
		}
		Relation updated = new Relation(rel);
		updated.setMembers(members);
		UndoRedoHandler.getInstance().add(new ChangeCommand(rel, updated));
		return "Relation " + id + " now has " + members.size() + " members (was " + rel.getMembersCount() + ")";
	}


	@Override
	public Category category() {
		return Category.GEOMETRY;
	}
}
