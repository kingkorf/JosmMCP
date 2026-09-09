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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

import com.fasterxml.jackson.databind.ObjectMapper;

class JosmUtilsTest {

	@BeforeAll
	static void setUpPreferences() {
		// OsmPrimitive's static initialiser reads preferences; give it an in-memory store.
		Config.setPreferencesInstance(new MemoryPreferences());
	}

	@Test
	void nodeIsSerialisedWithCoordinatesAndTags() throws Exception {
		Node n = new Node(new LatLon(52.37, 4.89));
		n.put("amenity", "cafe");

		Map<String, Object> m = JosmUtils.toMap(n);
		assertEquals(n.getUniqueId(), m.get("id"));
		assertEquals("node", m.get("type"));
		assertEquals(52.37, (Double) m.get("lat"), 1e-9);
		assertEquals(4.89, (Double) m.get("lon"), 1e-9);
		assertEquals(Map.of("amenity", "cafe"), m.get("tags"));
		assertNull(m.get("user"));
		assertFalse(m.containsKey("incomplete"));

		// must be valid JSON that round-trips
		Map<?, ?> parsed = new ObjectMapper().readValue(JosmUtils.printElement(n), Map.class);
		assertEquals("node", parsed.get("type"));
	}

	@Test
	void incompleteNodeHasNoCoordinates() {
		Node n = new Node(42L);
		Map<String, Object> m = JosmUtils.toMap(n);
		assertEquals(Boolean.TRUE, m.get("incomplete"));
		assertFalse(m.containsKey("lat"));
	}

	@Test
	void wayListsNodeIdsOnly() {
		Node a = new Node(new LatLon(0, 0));
		Node b = new Node(new LatLon(1, 1));
		Way w = new Way();
		w.addNode(a);
		w.addNode(b);

		Map<String, Object> m = JosmUtils.toMap(w);
		assertEquals("way", m.get("type"));
		assertEquals(List.of(a.getUniqueId(), b.getUniqueId()), m.get("node_ids"));
	}

	@Test
	void relationListsMembersWithRoles() {
		Node n = new Node(new LatLon(0, 0));
		Relation r = new Relation();
		r.addMember(new RelationMember("stop", n));

		Map<String, Object> m = JosmUtils.toMap(r);
		assertEquals("relation", m.get("type"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> members = (List<Map<String, Object>>) m.get("members");
		assertEquals(1, members.size());
		assertEquals("node", members.get(0).get("type"));
		assertEquals(n.getUniqueId(), members.get(0).get("ref"));
		assertEquals("stop", members.get(0).get("role"));
		assertTrue(m.containsKey("version"));
	}
}
