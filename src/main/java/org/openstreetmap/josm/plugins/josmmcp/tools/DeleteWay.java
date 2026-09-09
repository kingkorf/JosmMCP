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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

public class DeleteWay extends BaseTool {

	@Override
	public String getName() {
		return "delete_way";
	}

	@Override
	public String getDescription() {
		return "Delete a way by Id in current Dataset";
	}

	@Override
	public JsonSchema getInputSchema() {
		Map<String, Object> deleteProps = new HashMap<>();
		Map<String, Object> idProp = new HashMap<>();
		idProp.put("type", "integer");
		deleteProps.put("id", idProp);
		McpSchema.JsonSchema deleteSchema = new McpSchema.JsonSchema("object", deleteProps, Arrays.asList("id"), null,
				null, null);
		return deleteSchema;
	}

	@Override
	public String handle(Map<String, Object> args) throws Exception {
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds == null) {
			throw new Exception("no active dataset found");
		}

		long id = getLong(args, "id");
		Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
		if (w == null) {
			throw new Exception("Way with id " + id + " not found");
		}

		// DeleteCommand.delete() also removes the primitive from referencing ways/relations,
		// unlike the plain constructor which would leave dangling references behind.
		// alsoDeleteNodesInWay=true: drop untagged nodes used by no other way, as JOSM's Delete does.
		Command c = DeleteCommand.delete(Collections.singleton(w), true, true);
		if (c == null) {
			throw new Exception("Way with id " + id + " could not be deleted");
		}
		UndoRedoHandler.getInstance().add(c);
		return "Way " + id + " deleted (including its untagged, otherwise unused nodes)";
	}



	@Override
	public Category category() {
		return Category.DELETE;
	}

	@Override
	protected String describeForConfirmation(Map<String, Object> args) {
		try {
			long id = getLong(args, "id");
			org.openstreetmap.josm.data.osm.DataSet ds = MainApplication.getLayerManager().getEditDataSet();
			org.openstreetmap.josm.data.osm.OsmPrimitive p = ds == null ? null
					: ds.getPrimitiveById(new SimplePrimitiveId(id, OsmPrimitiveType.WAY));
			if (p == null) {
				return "Delete way " + id;
			}
			StringBuilder sb = new StringBuilder("Delete way " + id);
			if (p.hasKeys()) {
				sb.append(" with tags ").append(p.getKeys());
			}
			return sb.toString();
		} catch (Exception e) {
			return "Delete way " + args;
		}
	}
}
