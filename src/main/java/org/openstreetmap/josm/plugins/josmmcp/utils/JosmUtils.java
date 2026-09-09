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
package org.openstreetmap.josm.plugins.josmmcp.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Helpers to turn JOSM primitives into JSON so MCP clients can parse tool output.
 */
public final class JosmUtils {
	private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private JosmUtils() {
	}

	/**
	 * Serialises any value (maps, lists, primitives) to pretty-printed JSON.
	 */
	public static String toJson(Object value) throws JsonProcessingException {
		return MAPPER.writeValueAsString(value);
	}

	/**
	 * Returns a JSON representation of the given primitive.
	 */
	public static String printElement(OsmPrimitive el) throws JsonProcessingException {
		return toJson(toMap(el));
	}

	/**
	 * Converts a primitive to an ordered map suitable for JSON serialisation.
	 */
	public static Map<String, Object> toMap(OsmPrimitive el) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", el.getUniqueId());
		m.put("type", el.getType().getAPIName());
		if (el.isIncomplete()) {
			m.put("incomplete", true);
		}
		if (el.isDeleted()) {
			m.put("deleted", true);
		}
		if (el.isModified()) {
			m.put("modified", true);
		}

		switch (el.getType()) {
		case NODE:
			LatLon ll = ((Node) el).getCoor();
			if (ll != null) {
				m.put("lat", ll.lat());
				m.put("lon", ll.lon());
			}
			break;
		case WAY:
			List<Long> nodeIds = new ArrayList<>();
			for (Node n : ((Way) el).getNodes()) {
				nodeIds.add(n.getUniqueId());
			}
			m.put("node_ids", nodeIds);
			break;
		case RELATION:
			List<Map<String, Object>> members = new ArrayList<>();
			for (RelationMember rm : ((Relation) el).getMembers()) {
				Map<String, Object> mm = new LinkedHashMap<>();
				mm.put("type", rm.getType().getAPIName());
				mm.put("ref", rm.getUniqueId());
				mm.put("role", rm.getRole());
				members.add(mm);
			}
			m.put("members", members);
			break;
		default:
			m.put("unsupported_type", el.getType().toString().toLowerCase(Locale.ROOT));
		}

		m.put("tags", el.getKeys());
		m.put("version", el.getVersion());
		m.put("user", el.getUser() == null ? null : el.getUser().getName());
		return m;
	}
}
