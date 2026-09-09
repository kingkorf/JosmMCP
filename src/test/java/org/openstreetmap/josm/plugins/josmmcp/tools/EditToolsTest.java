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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exercises the editing tools against a small in-memory layer, without a JOSM window.
 */
class EditToolsTest {
	private static final ObjectMapper JSON = new ObjectMapper();
	private DataSet ds;
	private OsmDataLayer layer;

	@TempDir
	static Path josmHome;

	@BeforeAll
	static void setUpJosm() {
		Config.setPreferencesInstance(new MemoryPreferences());
		// Layers touch JOSM's cache manager, which needs base directories; point them at a temp dir.
		Config.setBaseDirectoriesProvider(new IBaseDirectories() {
			private File dir(String name, boolean create) {
				File f = josmHome.resolve(name).toFile();
				if (create) {
					f.mkdirs();
				}
				return f;
			}

			@Override
			public File getPreferencesDirectory(boolean createIfMissing) {
				return dir("prefs", createIfMissing);
			}

			@Override
			public File getUserDataDirectory(boolean createIfMissing) {
				return dir("data", createIfMissing);
			}

			@Override
			public File getCacheDirectory(boolean createIfMissing) {
				return dir("cache", createIfMissing);
			}
		});
		ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
	}

	@BeforeEach
	void createLayer() {
		ds = new DataSet();
		layer = new OsmDataLayer(ds, "test", null);
		MainApplication.getLayerManager().addLayer(layer);
		MainApplication.getLayerManager().setActiveLayer(layer);
	}

	@AfterEach
	void removeLayer() {
		UndoRedoHandler.getInstance().clean();
		MainApplication.getLayerManager().removeLayer(layer);
	}

	private static Map<String, Object> args(Object... kv) {
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private Way square() throws Exception {
		List<Long> ids = Arrays.asList(
				Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.0))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.001))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.001, "longitude", 5.001))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.001, "longitude", 5.0))));
		long wid = Long.parseLong(new CreateWay().handle(args("node_ids", Arrays.asList(ids.get(0), ids.get(1), ids.get(2), ids.get(3), ids.get(0)))));
		return (Way) ds.getPrimitiveById(new SimplePrimitiveId(wid, OsmPrimitiveType.WAY));
	}

	@Test
	void createAndTagAWay() throws Exception {
		Way w = square();
		assertTrue(w.isClosed());
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes", "note", "x")));
		assertEquals("yes", w.get("building"));
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("note", "")));
		assertNull(w.get("note"));
		// 4 nodes + way + two tag commands
		assertEquals(7, UndoRedoHandler.getInstance().getUndoCommands().size());
	}

	@Test
	void batchTagsIsOneUndoStep() throws Exception {
		Way w = square();
		int before = UndoRedoHandler.getInstance().getUndoCommands().size();
		String out = new ModifyTagsBatch().handle(args("changes", Arrays.asList(
				Map.of("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("a", "1", "b", "2")),
				Map.of("element_type", "node", "element_id", w.getNode(0).getUniqueId(), "tags", Map.of("c", "3"))),
				"description", "Test batch"));
		JsonNode r = JSON.readTree(out);
		assertEquals(2, r.path("elements").asInt());
		assertEquals(3, r.path("tag_changes").asInt());
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size());
		assertEquals("3", w.getNode(0).get("c"));
	}

	@Test
	void replaceGeometryMovesFreeNodesAndKeepsSharedOnes() throws Exception {
		Way w = square();
		// share one node with a second way (a fence)
		Node shared = w.getNode(0);
		long other = Long.parseLong(new CreateNode().handle(args("latitude", 52.002, "longitude", 5.0)));
		new CreateWay().handle(args("node_ids", Arrays.asList(shared.getUniqueId(), other)));
		LatLon sharedBefore = shared.getCoor();

		String out = new ReplaceGeometry().handle(args("id", w.getUniqueId(), "coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.002, 52.0), Arrays.asList(5.002, 52.002), Arrays.asList(5.001, 52.002),
				Arrays.asList(5.0, 52.002), Arrays.asList(5.0, 52.0))));
		JsonNode r = JSON.readTree(out);
		assertEquals(5, r.path("vertices").asInt());
		assertTrue(r.path("closed").asBoolean());
		assertEquals(1, r.path("shared_or_tagged_nodes_kept").asInt(), out);
		// three free nodes are moved to the nearest new vertices, the fifth vertex needs a new node
		assertEquals(1, r.path("nodes_created").asInt(), out);
		assertEquals(3, r.path("nodes_moved").asInt(), out);
		assertEquals(0, r.path("nodes_deleted").asInt(), out);
		assertEquals(6, w.getNodesCount());
		assertEquals(sharedBefore, shared.getCoor(), "shared node must not move");
		assertTrue(w.containsNode(shared));
	}

	@Test
	void replaceGeometryDeletesSurplusFreeNodes() throws Exception {
		Way w = square();
		String out = new ReplaceGeometry().handle(args("id", w.getUniqueId(), "coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.001, 52.0), Arrays.asList(5.0005, 52.001))));
		JsonNode r = JSON.readTree(out);
		assertEquals(1, r.path("nodes_deleted").asInt(), out);
		assertEquals(4, w.getNodesCount()); // 3 vertices + closing node
		assertEquals(3, ds.getNodes().stream().filter(n -> !n.isDeleted()).count());
	}

	@Test
	void updateWayNodesAndDeleteWayWithNodes() throws Exception {
		Way w = square();
		Node extra = (Node) ds.getPrimitiveById(new SimplePrimitiveId(
				Long.parseLong(new CreateNode().handle(args("latitude", 52.0005, "longitude", 5.0))), OsmPrimitiveType.NODE));
		new UpdateWayNodes().handle(args("id", w.getUniqueId(), "node_ids", Arrays.asList(
				w.getNode(0).getUniqueId(), w.getNode(1).getUniqueId(), w.getNode(2).getUniqueId(), extra.getUniqueId(), w.getNode(0).getUniqueId())));
		assertEquals(5, w.getNodesCount());
		assertTrue(w.containsNode(extra));
		String msg = new DeleteWay().handle(args("id", w.getUniqueId()));
		assertTrue(msg.contains("deleted"));
		assertTrue(w.isDeleted());
		assertEquals(1, ds.getNodes().stream().filter(n -> !n.isDeleted()).count(), "only the dropped node survives");
	}

	@Test
	void createRelationAndUpdateMembers() throws Exception {
		Way w = square();
		long rid = Long.parseLong(new CreateRelation().handle(args("tags", Map.of("type", "multipolygon", "landuse", "grass"),
				"members", Arrays.asList(Map.of("type", "way", "ref", w.getUniqueId(), "role", "outer")))));
		assertNotNull(ds.getPrimitiveById(new SimplePrimitiveId(rid, OsmPrimitiveType.RELATION)));
		String out = new UpdateRelation().handle(args("id", rid, "members", Arrays.asList(
				Map.of("type", "way", "ref", w.getUniqueId(), "role", "outer"),
				Map.of("type", "node", "ref", w.getNode(0).getUniqueId(), "role", "label"))));
		assertTrue(out.contains("2 members"));
	}

	@Test
	void undoAndRedoTools() throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));
		JsonNode r = JSON.readTree(new UndoRedoTool(UndoRedoTool.Mode.UNDO).handle(args("n", 1)));
		assertEquals(1, r.path("undone").size());
		assertNull(w.get("building"));
		JSON.readTree(new UndoRedoTool(UndoRedoTool.Mode.REDO).handle(args()));
		assertEquals("yes", w.get("building"));
		JsonNode list = JSON.readTree(new UndoRedoTool(UndoRedoTool.Mode.LIST).handle(args("limit", 3)));
		assertEquals(3, list.path("undo_stack").size());
		assertFalse(list.path("redo_stack").iterator().hasNext());
	}

	@Test
	void searchWithBboxFieldsAndOffset() throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));
		JsonNode r = JSON.readTree(new SearchTool().handle(args("query", "building=yes", "bbox", Arrays.asList(4.999, 51.999, 5.002, 52.002), "fields", Arrays.asList("tags"))));
		assertEquals(1, r.path("total_matches").asInt());
		JsonNode el = r.path("elements").get(0);
		assertTrue(el.has("tags"));
		assertFalse(el.has("node_ids"));
		JsonNode none = JSON.readTree(new SearchTool().handle(args("query", "building=yes", "bbox", Arrays.asList(6.0, 52.0, 6.1, 52.1))));
		assertEquals(0, none.path("total_matches").asInt());
		JsonNode paged = JSON.readTree(new SearchTool().handle(args("query", "type:node", "max_results", 2, "offset", 3)));
		assertEquals(4, paged.path("total_matches").asInt());
		assertEquals(1, paged.path("returned").asInt());
	}

	@Test
	void pendingChangesSummaryCountsStates() throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));
		JsonNode r = JSON.readTree(new PendingChangesSummary().handle(args()));
		assertEquals(5, r.path("total_changed_objects").asInt());
		assertEquals(1, r.path("counts").path("way_new").asInt());
		assertEquals(4, r.path("counts").path("node_new").asInt());
		assertTrue(r.path("tag_keys_on_changed_objects").has("building"));
		assertTrue(r.has("bbox"));
	}

	@Test
	void undoRefusesForeignCommandsUnlessForced() throws Exception {
		Way w = square();
		// a command not made through the plugin: no "(MCP)" marker
		UndoRedoHandler.getInstance().add(new org.openstreetmap.josm.command.ChangePropertyCommand(w, "foo", "bar"));
		assertEquals("bar", w.get("foo"));
		Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> new UndoRedoTool(UndoRedoTool.Mode.UNDO).handle(args("n", 1)));
		assertTrue(e.getMessage().contains("not made through this plugin"), e.getMessage());
		assertEquals("bar", w.get("foo"), "nothing may be undone when refused");
		new UndoRedoTool(UndoRedoTool.Mode.UNDO).handle(args("n", 1, "force", true));
		assertNull(w.get("foo"));
		JsonNode list = JSON.readTree(new UndoRedoTool(UndoRedoTool.Mode.LIST).handle(args("limit", 2)));
		assertTrue(list.path("undo_stack").get(0).path("by_plugin").asBoolean());
	}

	@Test
	void searchByPolygonAndRadius() throws Exception {
		Way w = square(); // corners (5.0,52.0) .. (5.001,52.001)
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));
		JsonNode inside = JSON.readTree(new SearchTool().handle(args("query", "building=yes", "polygon",
				Arrays.asList(Arrays.asList(4.999, 51.999), Arrays.asList(5.002, 51.999), Arrays.asList(5.002, 52.002), Arrays.asList(4.999, 52.002)))));
		assertEquals(1, inside.path("total_matches").asInt());
		JsonNode outside = JSON.readTree(new SearchTool().handle(args("query", "building=yes", "polygon",
				Arrays.asList(Arrays.asList(5.01, 52.01), Arrays.asList(5.02, 52.01), Arrays.asList(5.02, 52.02)))));
		assertEquals(0, outside.path("total_matches").asInt());
		JsonNode near = JSON.readTree(new SearchTool().handle(args("query", "type:node", "center", Arrays.asList(5.0, 52.0), "radius_m", 10)));
		assertEquals(1, near.path("total_matches").asInt(), "only the corner node at the centre is within 10 m");
		JsonNode wide = JSON.readTree(new SearchTool().handle(args("query", "type:node", "center", Arrays.asList(5.0, 52.0), "radius_m", 200)));
		assertEquals(4, wide.path("total_matches").asInt());
	}

	@Test
	void readRelationWithGeometry() throws Exception {
		Way w = square();
		long rid = Long.parseLong(new CreateRelation().handle(args("tags", Map.of("type", "multipolygon"),
				"members", Arrays.asList(Map.of("type", "way", "ref", w.getUniqueId(), "role", "outer"),
						Map.of("type", "node", "ref", w.getNode(0).getUniqueId(), "role", "label")))));
		JsonNode r = JSON.readTree(new ReadRelation().handle(args("id", rid, "include_geometry", true)));
		assertEquals(2, r.path("members").size());
		assertEquals(5, r.path("members").get(0).path("nodes").size());
		assertTrue(r.path("members").get(1).has("lat"));
	}
}
