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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.josmmcp.utils.PlanarGeometry;
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
		// three free nodes are moved to the nearest new vertices; the fifth vertex coincides with the
		// fence's other node, which is reused (glued) instead of getting a duplicate node
		assertEquals(0, r.path("nodes_created").asInt(), out);
		assertEquals(1, r.path("nodes_glued_to_other_ways").asInt(), out);
		assertTrue(w.containsNode((Node) ds.getPrimitiveById(new SimplePrimitiveId(other, OsmPrimitiveType.NODE))));
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

	@Test
	void createNodesReturnsIdsInOrderAsOneUndoStep() throws Exception {
		int before = UndoRedoHandler.getInstance().getUndoCommands().size();
		JsonNode r = JSON.readTree(new CreateNodes().handle(args("coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.001, 52.0), Arrays.asList(5.001, 52.001)))));
		assertEquals(3, r.path("created").asInt());
		assertEquals(3, r.path("ids").size());
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size());
		Node second = (Node) ds.getPrimitiveById(new SimplePrimitiveId(r.path("ids").get(1).asLong(), OsmPrimitiveType.NODE));
		assertNotNull(second);
		assertEquals(new LatLon(52.0, 5.001), second.getCoor());
		// the ids can be used directly for a way
		long wid = Long.parseLong(new CreateWay().handle(args("node_ids", Arrays.asList(
				r.path("ids").get(0).asLong(), r.path("ids").get(1).asLong(), r.path("ids").get(2).asLong()))));
		assertEquals(3, ((Way) ds.getPrimitiveById(new SimplePrimitiveId(wid, OsmPrimitiveType.WAY))).getNodesCount());
		Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> new CreateNodes().handle(args("coordinates", Arrays.asList(Arrays.asList(5.0, 52.0), Arrays.asList(500.0, 52.0)))));
		assertTrue(e.getMessage().contains("nothing was created"), e.getMessage());
		assertEquals(3, ds.getNodes().size(), "an invalid batch creates nothing");
	}

	@Test
	void updateNodesMovesAllOrNothing() throws Exception {
		Way w = square();
		int before = UndoRedoHandler.getInstance().getUndoCommands().size();
		JsonNode r = JSON.readTree(new UpdateNodes().handle(args("moves", Arrays.asList(
				Map.of("id", w.getNode(0).getUniqueId(), "lon", 5.0005, "lat", 52.0005),
				Map.of("id", w.getNode(1).getUniqueId(), "lon", 5.0015, "lat", 52.0005)), "description", "Nudge")));
		assertEquals(2, r.path("moved").asInt());
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size());
		assertEquals(new LatLon(52.0005, 5.0005), w.getNode(0).getCoor());
		assertTrue(UndoRedoHandler.getInstance().getLastCommand().getDescriptionText().contains("Nudge"));
		Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> new UpdateNodes().handle(args("moves", Arrays.asList(
						Map.of("id", w.getNode(2).getUniqueId(), "lon", 5.1, "lat", 52.1),
						Map.of("id", 999999L, "lon", 5.1, "lat", 52.1)))));
		assertTrue(e.getMessage().contains("nothing was moved"), e.getMessage());
		assertEquals(new LatLon(52.001, 5.001), w.getNode(2).getCoor(), "a failed batch moves nothing");
	}

	@Test
	void deleteElementsRemovesWaysWithTheirNodesAndSingleNodes() throws Exception {
		Way w = square();
		Node shared = w.getNode(0);
		long other = Long.parseLong(new CreateNode().handle(args("latitude", 52.002, "longitude", 5.0)));
		long fence = Long.parseLong(new CreateWay().handle(args("node_ids", Arrays.asList(shared.getUniqueId(), other))));
		long loose = Long.parseLong(new CreateNode().handle(args("latitude", 52.003, "longitude", 5.0)));
		int before = UndoRedoHandler.getInstance().getUndoCommands().size();
		JsonNode r = JSON.readTree(new DeleteElements().handle(args("elements", Arrays.asList(
				Map.of("type", "way", "id", w.getUniqueId()), Map.of("type", "node", "id", loose)))));
		assertEquals(1, r.path("requested").path("way").asInt());
		assertEquals(1, r.path("requested").path("node").asInt());
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size());
		assertTrue(w.isDeleted());
		assertFalse(shared.isDeleted(), "node still used by the fence survives");
		// square: 3 free nodes + way, plus the loose node
		assertEquals(5, r.path("deleted").asInt());
		assertFalse(((Way) ds.getPrimitiveById(new SimplePrimitiveId(fence, OsmPrimitiveType.WAY))).isDeleted());
		Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
				() -> new DeleteElements().handle(args("elements", Arrays.asList(
						Map.of("type", "way", "id", fence), Map.of("type", "node", "id", 424242L)))));
		assertTrue(e.getMessage().contains("nothing was deleted"), e.getMessage());
		assertFalse(((Way) ds.getPrimitiveById(new SimplePrimitiveId(fence, OsmPrimitiveType.WAY))).isDeleted());
	}

	@Test
	void outputPathWritesResultAndReturnsSummary(@TempDir Path dir) throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));
		String full = new SearchTool().handle(args("query", "building=yes", "bbox", Arrays.asList(4.9, 51.9, 5.1, 52.1)));
		Path out = dir.resolve("result.json");
		JsonNode summary = JSON.readTree(BaseTool.writeOutput(full, out.toString()));
		assertEquals(out.toString(), summary.path("output_path").asText());
		assertEquals(full, java.nio.file.Files.readString(out));
		assertEquals(1, summary.path("result").path("total_matches").asInt());
		assertEquals("1 items in file", summary.path("result").path("elements").asText());
		assertEquals(4, summary.path("result").path("bbox").size(), "short scalar arrays stay in the summary");
		assertTrue(new SearchTool().getEffectiveInputSchema().properties().containsKey("output_path"));
		assertFalse(new CreateNode().getEffectiveInputSchema().properties().containsKey("output_path"));
		assertThrows(Exception.class, () -> BaseTool.writeOutput(full, dir.resolve("result.osm").toString()));
		assertThrows(Exception.class, () -> BaseTool.writeOutput(full, "relative.json"));
		assertThrows(Exception.class, () -> BaseTool.writeOutput(full, dir.resolve("missing/result.json").toString()));
	}

	@Test
	void replaceGeometryInsertsSharedNodesLyingOnNewSegments() throws Exception {
		// square with an extra node halfway the bottom edge, shared with a fence
		List<Long> ids = Arrays.asList(
				Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.0))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.0005))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.001))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.001, "longitude", 5.001))),
				Long.parseLong(new CreateNode().handle(args("latitude", 52.001, "longitude", 5.0))));
		long wid = Long.parseLong(new CreateWay().handle(args("node_ids", Arrays.asList(ids.get(0), ids.get(1), ids.get(2), ids.get(3), ids.get(4), ids.get(0)))));
		Way w = (Way) ds.getPrimitiveById(new SimplePrimitiveId(wid, OsmPrimitiveType.WAY));
		Node mid = w.getNode(1);
		long other = Long.parseLong(new CreateNode().handle(args("latitude", 51.999, "longitude", 5.0005)));
		new CreateWay().handle(args("node_ids", Arrays.asList(mid.getUniqueId(), other)));
		// new outline: the same square, 1 m taller, without the midpoint vertex
		JsonNode r = JSON.readTree(new ReplaceGeometry().handle(args("id", wid, "coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.001, 52.0), Arrays.asList(5.001, 52.00101), Arrays.asList(5.0, 52.00101), Arrays.asList(5.0, 52.0)))));
		assertEquals(1, r.path("shared_or_tagged_nodes_inserted_on_segments").asInt(), r.toString());
		assertEquals(0, r.path("shared_or_tagged_nodes_left_out_of_way").asInt(), r.toString());
		assertEquals(5, r.path("nodes_in_way").asInt(), r.toString());
		assertEquals(mid, w.getNode(1), "the shared midpoint stays between its neighbours");
		assertEquals(new LatLon(52.0, 5.0005), mid.getCoor(), "and is not moved");
		assertNoAdjacentDuplicates(w);
	}

	@Test
	void replaceGeometryGluesToNodesOfOtherWaysAndSetsExactCoordinates() throws Exception {
		Way w = square();
		// a neighbouring building with a corner exactly where the new outline will have a vertex
		long c1 = Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.002)));
		long c2 = Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.003)));
		long c3 = Long.parseLong(new CreateNode().handle(args("latitude", 52.001, "longitude", 5.003)));
		new CreateWay().handle(args("node_ids", Arrays.asList(c1, c2, c3, c1)));
		JsonNode r = JSON.readTree(new ReplaceGeometry().handle(args("id", w.getUniqueId(), "coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.002, 52.0), Arrays.asList(5.0020001, 52.0010001), Arrays.asList(5.0, 52.001)))));
		assertEquals(1, r.path("nodes_glued_to_other_ways").asInt(), r.toString());
		Node corner = (Node) ds.getPrimitiveById(new SimplePrimitiveId(c1, OsmPrimitiveType.NODE));
		assertTrue(w.containsNode(corner), "the building corner is reused, no duplicate node on top of it");
		assertEquals(2, corner.getReferrers().size());
		assertEquals(new LatLon(52.0010001, 5.0020001), w.getNode(2).getCoor(), "moved nodes get the exact coordinate");
		assertEquals(3, r.path("nodes_moved").asInt() + r.path("nodes_reused_in_place").asInt(), r.toString());
		assertEquals(1, r.path("nodes_deleted").asInt(), "the fourth free node is surplus");
		assertNoAdjacentDuplicates(w);
		// glue_m=0 switches the behaviour off: a fresh node is created instead
		Way w2 = square();
		JsonNode r2 = JSON.readTree(new ReplaceGeometry().handle(args("id", w2.getUniqueId(), "glue_m", 0, "coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.002, 52.0), Arrays.asList(5.002, 52.001), Arrays.asList(5.0, 52.001)))));
		assertEquals(0, r2.path("nodes_glued_to_other_ways").asInt());
		assertFalse(w2.containsNode(corner));
	}

	@Test
	void replaceGeometryNeverRepeatsANodeAndReportsDroppedSharedNodes() throws Exception {
		Way w = square();
		Node shared = w.getNode(2); // (5.001, 52.001) shared with a fence
		long other = Long.parseLong(new CreateNode().handle(args("latitude", 52.002, "longitude", 5.002)));
		new CreateWay().handle(args("node_ids", Arrays.asList(shared.getUniqueId(), other)));
		// two vertices within snap distance of the shared node, and an outline that leaves it far behind otherwise
		JsonNode r = JSON.readTree(new ReplaceGeometry().handle(args("id", w.getUniqueId(), "snap_m", 0.5, "coordinates", Arrays.asList(
				Arrays.asList(5.0, 52.0), Arrays.asList(5.001, 52.0), Arrays.asList(5.001, 52.001), Arrays.asList(5.0010001, 52.0010012), Arrays.asList(5.0, 52.001)))));
		assertEquals(1, r.path("shared_or_tagged_nodes_kept").asInt(), r.toString());
		assertNoAdjacentDuplicates(w);
		assertEquals(5, r.path("nodes_in_way").asInt(), "the second nearby vertex gets its own node, the shared node is used once");
		// now an outline far away from the shared node: it drops out and is reported by id
		JsonNode r3 = JSON.readTree(new ReplaceGeometry().handle(args("id", w.getUniqueId(), "coordinates", Arrays.asList(
				Arrays.asList(5.01, 52.01), Arrays.asList(5.011, 52.01), Arrays.asList(5.011, 52.011), Arrays.asList(5.01, 52.011)))));
		assertEquals(1, r3.path("shared_or_tagged_nodes_left_out_of_way").asInt(), r3.toString());
		assertEquals(shared.getUniqueId(), r3.path("left_out_node_ids").get(0).asLong());
		assertFalse(w.containsNode(shared));
		assertFalse(shared.isDeleted(), "the fence keeps its node");
	}

	private static void assertNoAdjacentDuplicates(Way w) {
		for (int i = 1; i < w.getNodesCount(); i++) {
			assertTrue(w.getNode(i) != w.getNode(i - 1), "node " + w.getNode(i).getUniqueId() + " repeated at " + i);
		}
	}

	@Test
	void findDuplicateNodesAndMergeThem() throws Exception {
		Way w = square(); // corners (5.0,52.0) (5.001,52.0) (5.001,52.001) (5.0,52.001)
		// a second outline drawn along the square without gluing: two nodes on top of corners, one 3 cm off
		long d1 = Long.parseLong(new CreateNode().handle(args("latitude", 52.0, "longitude", 5.001)));
		long d2 = Long.parseLong(new CreateNode().handle(args("latitude", 52.001, "longitude", 5.001)));
		long d3 = Long.parseLong(new CreateNode().handle(args("latitude", 52.0010003, "longitude", 5.0)));
		long other = Long.parseLong(new CreateWay().handle(args("node_ids", Arrays.asList(d1, d2, d3))));
		// a tagged duplicate that conflicts with nothing (corner is untagged)
		new ModifyTags().handle(args("element_type", "node", "element_id", d1, "tags", Map.of("entrance", "yes")));

		JsonNode exact = JSON.readTree(new FindDuplicateNodes().handle(args()));
		assertEquals(2, exact.path("total_groups").asInt(), exact.toString());
		assertEquals(0, exact.path("groups_with_conflicts").asInt());
		assertTrue(exact.path("groups").get(0).path("mergeable").asBoolean());
		assertEquals(2, exact.path("groups").get(0).path("nodes").get(0).path("ways").size() + exact.path("groups").get(0).path("nodes").get(1).path("ways").size());

		JsonNode near = JSON.readTree(new FindDuplicateNodes().handle(args("tolerance_m", 0.1, "bbox", Arrays.asList(4.9, 51.9, 5.1, 52.1))));
		assertEquals(3, near.path("total_groups").asInt(), near.toString());
		JsonNode untagged = JSON.readTree(new FindDuplicateNodes().handle(args("tolerance_m", 0.1, "untagged_only", true)));
		assertEquals(2, untagged.path("total_groups").asInt());

		// a conflicting group: two tagged nodes with different values
		new ModifyTags().handle(args("element_type", "node", "element_id", w.getNode(2).getUniqueId(), "tags", Map.of("entrance", "main")));
		new ModifyTags().handle(args("element_type", "node", "element_id", d2, "tags", Map.of("entrance", "service")));
		JsonNode conflicts = JSON.readTree(new FindDuplicateNodes().handle(args()));
		assertEquals(1, conflicts.path("groups_with_conflicts").asInt(), conflicts.toString());

		List<List<Long>> groups = Arrays.asList(
				Arrays.asList(w.getNode(1).getUniqueId(), d1),
				Arrays.asList(w.getNode(2).getUniqueId(), d2),
				Arrays.asList(w.getNode(3).getUniqueId(), d3));
		Exception e = assertThrows(Exception.class, () -> new MergeNodes().handle(args("groups", groups)));
		assertTrue(e.getMessage().contains("nothing was merged"), e.getMessage());
		assertFalse(((Node) ds.getPrimitiveById(new SimplePrimitiveId(d1, OsmPrimitiveType.NODE))).isDeleted());

		int before = UndoRedoHandler.getInstance().getUndoCommands().size();
		JsonNode r = JSON.readTree(new MergeNodes().handle(args("groups", groups, "skip_conflicts", true, "keep_first", true)));
		assertEquals(2, r.path("groups_merged").asInt(), r.toString());
		assertEquals(1, r.path("groups_skipped").asInt());
		assertEquals(2, r.path("nodes_removed").asInt());
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size());
		Way o = (Way) ds.getPrimitiveById(new SimplePrimitiveId(other, OsmPrimitiveType.WAY));
		assertTrue(o.containsNode(w.getNode(1)), "the other way now uses the square's corner");
		assertTrue(o.containsNode(w.getNode(3)));
		assertEquals("yes", w.getNode(1).get("entrance"), "tags of the merged node are kept");
		assertEquals(new LatLon(52.001, 5.0), w.getNode(3).getCoor(), "the survivor keeps its position");
		assertTrue(((Node) ds.getPrimitiveById(new SimplePrimitiveId(d1, OsmPrimitiveType.NODE))).isDeleted());
		assertFalse(((Node) ds.getPrimitiveById(new SimplePrimitiveId(d2, OsmPrimitiveType.NODE))).isDeleted(), "the conflicting group is untouched");
		assertEquals(1, JSON.readTree(new FindDuplicateNodes().handle(args())).path("total_groups").asInt());
	}

	private long node(double lon, double lat) throws Exception {
		return Long.parseLong(new CreateNode().handle(args("latitude", lat, "longitude", lon)));
	}

	private Way way(String key, String value, Long... nodeIds) throws Exception {
		List<Long> ids = new ArrayList<>(Arrays.asList(nodeIds));
		ids.add(nodeIds[0]);
		long wid = Long.parseLong(new CreateWay().handle(args("node_ids", ids)));
		new ModifyTags().handle(args("element_type", "way", "element_id", wid, "tags", Map.of(key, value)));
		return (Way) ds.getPrimitiveById(new SimplePrimitiveId(wid, OsmPrimitiveType.WAY));
	}

	/** Residential square (about 100 m), retail square east of it sharing the edge, a building on the edge. */
	private Way[] twoAreasAndABuilding() throws Exception {
		long a0 = node(5.0, 52.0), a1 = node(5.0015, 52.0), a2 = node(5.0015, 52.0009), a3 = node(5.0, 52.0009);
		long n1 = node(5.003, 52.0), n2 = node(5.003, 52.0009);
		Way res = way("landuse", "residential", a0, a1, a2, a3);
		Way ret = way("landuse", "retail", a1, n1, n2, a2);
		// 20 % of the building is in the residential area, 80 % in retail
		Way b = way("building", "house", node(5.00147, 52.0004), node(5.00161, 52.0004), node(5.00161, 52.00049), node(5.00147, 52.00049));
		return new Way[] {res, ret, b};
	}

	private static double share(PlanarGeometry pg, Way building, Way area) {
		java.awt.geom.Area x = pg.area(building);
		x.intersect(pg.area(area));
		return PlanarGeometry.squareMetres(x) / PlanarGeometry.squareMetres(pg.area(building));
	}

	@Test
	void reshapeAreaMovesABuildingOutAndGivesTheGroundToTheNeighbour() throws Exception {
		Way[] w = twoAreasAndABuilding();
		Way res = w[0], ret = w[1], b = w[2];
		PlanarGeometry pg = new PlanarGeometry(52.0);
		assertEquals(0.2, share(pg, b, res), 0.02);
		double resBefore = PlanarGeometry.squareMetres(pg.area(res));
		double retBefore = PlanarGeometry.squareMetres(pg.area(ret));

		JsonNode dry = JSON.readTree(new ReshapeArea().handle(args("id", res.getUniqueId(), "exclude", Arrays.asList(b.getUniqueId()), "dry_run", true)));
		assertTrue(dry.path("dry_run").asBoolean());
		assertEquals(ret.getUniqueId(), dry.path("buildings").get(0).path("ground_goes_to").asLong(), dry.toString());
		assertEquals(2, dry.path("ways").size());
		assertEquals(4, res.getNodesCount() - 1, "dry run changes nothing");
		int before = UndoRedoHandler.getInstance().getUndoCommands().size();

		JsonNode r = JSON.readTree(new ReshapeArea().handle(args("id", res.getUniqueId(), "exclude", Arrays.asList(b.getUniqueId()))));
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size(), "one undo step for both ways");
		assertEquals(0.0, share(pg, b, res), 1e-6, "building is out of the residential area");
		assertEquals(1.0, share(pg, b, ret), 1e-6, "and completely inside the retail area");
		java.awt.geom.Area overlap = pg.area(res);
		overlap.intersect(pg.area(ret));
		assertEquals(0.0, PlanarGeometry.squareMetres(overlap), 0.01, "the two areas do not overlap");
		double resAfter = PlanarGeometry.squareMetres(pg.area(res));
		double retAfter = PlanarGeometry.squareMetres(pg.area(ret));
		assertEquals(resBefore + retBefore, resAfter + retAfter, 0.5, "ground is transferred, not lost");
		assertTrue(resAfter < resBefore && retAfter > retBefore);
		// the notch around the building: the shared boundary now has more than the two original corner nodes
		long shared = res.getNodes().stream().distinct().filter(n -> ret.containsNode(n)).count();
		assertTrue(shared >= 5, "areas share the notch nodes, found " + shared);
		assertEquals(1.0, share(pg, b, ret), 1e-6);
		assertNoAdjacentDuplicates(res);
		assertNoAdjacentDuplicates(ret);
		assertTrue(r.path("ways").get(0).has("replace"), r.toString());
	}

	@Test
	void reshapeAreaCanAlsoPullABuildingInAndRefusesSplits() throws Exception {
		Way[] w = twoAreasAndABuilding();
		Way res = w[0], ret = w[1], b = w[2];
		PlanarGeometry pg = new PlanarGeometry(52.0);
		new ReshapeArea().handle(args("id", res.getUniqueId(), "include", Arrays.asList(b.getUniqueId())));
		assertEquals(1.0, share(pg, b, res), 1e-6, "building is inside the residential area");
		assertEquals(0.0, share(pg, b, ret), 1e-6, "and out of the retail area");
		java.awt.geom.Area overlap = pg.area(res);
		overlap.intersect(pg.area(ret));
		assertEquals(0.0, PlanarGeometry.squareMetres(overlap), 0.01);

		// a building wider than the area would cut it in two: refused, nothing changed
		Way bar = way("building", "yes", node(4.9999, 52.0007), node(5.0016, 52.0007), node(5.0016, 52.00072), node(4.9999, 52.00072));
		int nodesBefore = res.getNodesCount();
		Exception e = assertThrows(Exception.class, () -> new ReshapeArea().handle(args("id", res.getUniqueId(), "exclude", Arrays.asList(bar.getUniqueId()))));
		assertTrue(e.getMessage().contains("rings"), e.getMessage());
		assertEquals(nodesBefore, res.getNodesCount());
	}

	@Test
	void searchByIdsAndFindOrphanNodes() throws Exception {
		Way w = square();
		long loose = node(5.05, 52.05);
		long farAway = node(6.0, 53.0);
		new ModifyTags().handle(args("element_type", "node", "element_id", farAway, "tags", Map.of("amenity", "bench")));
		// ids without a query: everything with these ids, of any type
		JsonNode r = JSON.readTree(new SearchTool().handle(args("ids", Arrays.asList(w.getUniqueId(), loose, 424242L))));
		assertEquals(2, r.path("total_matches").asInt(), r.toString());
		// ids plus bbox: which of these lie in the area
		JsonNode inBox = JSON.readTree(new SearchTool().handle(args("ids", Arrays.asList(w.getUniqueId(), loose),
				"bbox", Arrays.asList(4.99, 51.99, 5.01, 52.01))));
		assertEquals(1, inBox.path("total_matches").asInt());
		assertEquals(w.getUniqueId(), inBox.path("elements").get(0).path("id").asLong());
		// ids plus query
		JsonNode typed = JSON.readTree(new SearchTool().handle(args("ids", Arrays.asList(w.getUniqueId(), loose), "query", "type:node")));
		assertEquals(1, typed.path("total_matches").asInt());
		assertThrows(Exception.class, () -> new SearchTool().handle(args("max_results", 5)));

		JsonNode orphans = JSON.readTree(new FindOrphanNodes().handle(args()));
		assertEquals(1, orphans.path("total").asInt(), "only the loose untagged node; the bench is tagged, the corners are in a way");
		assertEquals(loose, orphans.path("ids").get(0).asLong());
		JsonNode none = JSON.readTree(new FindOrphanNodes().handle(args("bbox", Arrays.asList(4.99, 51.99, 5.01, 52.01))));
		assertEquals(0, none.path("total").asInt());
	}

	@Test
	void validateByBboxElementsAndTestFilter() throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));
		// a residential area whose edge cuts through the building
		Way res = way("landuse", "residential", node(4.999, 51.999), node(5.0005, 51.999), node(5.0005, 52.002), node(4.999, 52.002));
		JsonNode box = JSON.readTree(new ValidateTool().handle(args("bbox", Arrays.asList(4.99, 51.99, 5.01, 52.01), "tests", Arrays.asList("crossing", "kruisend"))));
		assertEquals("bbox", box.path("scope").asText());
		assertTrue(box.path("validated_elements").asInt() >= 2);
		assertTrue(box.path("tests_run").asInt() >= 1 && box.path("tests_run").asInt() < 10, box.toString());
		assertEquals(1, box.path("warnings").asInt(), box.toString());
		JsonNode els = JSON.readTree(new ValidateTool().handle(args("elements", Arrays.asList(Map.of("type", "way", "id", res.getUniqueId())), "max_findings", 0)));
		assertEquals("elements", els.path("scope").asText());
		assertEquals(1, els.path("warnings").asInt(), els.toString());
		assertEquals(0, els.path("findings").size());
		assertEquals(1, els.path("findings_omitted").asInt());
		JsonNode nodeScope = JSON.readTree(new ValidateTool().handle(args("elements", Arrays.asList(Map.of("type", "node", "id", w.getNode(0).getUniqueId())))));
		assertTrue(nodeScope.path("validated_elements").asInt() >= 2, "a node brings its parent way");
	}

	/** Open way of five nodes running east, tagged so the parts can be told apart. */
	private Way openLine(String key, String value, int count) throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(node(5.0 + i * 0.0002, 52.0));
		}
		long wid = Long.parseLong(new CreateWay().handle(args("node_ids", ids)));
		new ModifyTags().handle(args("element_type", "way", "element_id", wid, "tags", Map.of(key, value)));
		return (Way) ds.getPrimitiveById(new SimplePrimitiveId(wid, OsmPrimitiveType.WAY));
	}

	@Test
	void splitWayKeepsIdOnOnePartAndCopiesTags() throws Exception {
		Way w = openLine("waterway", "ditch", 5);
		long original = w.getUniqueId();
		Node at = w.getNode(2);
		int before = UndoRedoHandler.getInstance().getUndoCommands().size();

		JsonNode r = JSON.readTree(new SplitWay().handle(args("id", original, "at_node_ids",
				Arrays.asList(at.getUniqueId()))));
		assertEquals(2, r.path("parts").asInt(), r.toString());
		assertEquals(original, r.path("kept_way").path("id").asLong());
		assertEquals(1, r.path("new_ways").size());
		assertEquals(before + 1, UndoRedoHandler.getInstance().getUndoCommands().size(), "one undo step");

		long other = r.path("new_ways").get(0).path("id").asLong();
		Way part = (Way) ds.getPrimitiveById(new SimplePrimitiveId(other, OsmPrimitiveType.WAY));
		assertNotNull(part);
		assertEquals("ditch", part.get("waterway"), "the new part carries the tags of the original");
		assertEquals(3, w.getNodesCount());
		assertEquals(3, part.getNodesCount());
		assertTrue(w.containsNode(at) && part.containsNode(at), "both parts share the split node");
	}

	@Test
	void splitWayAtTwoNodesGivesThreeParts() throws Exception {
		Way w = openLine("highway", "path", 6);
		JsonNode r = JSON.readTree(new SplitWay().handle(args("id", w.getUniqueId(), "at_node_ids",
				Arrays.asList(w.getNode(2).getUniqueId(), w.getNode(4).getUniqueId()), "keep", "first")));
		assertEquals(3, r.path("parts").asInt(), r.toString());
		assertEquals(2, r.path("new_ways").size());
		// with keep=first the kept way is the first chunk: nodes 0..2
		assertEquals(3, r.path("kept_way").path("nodes").asInt(), r.toString());
	}

	@Test
	void splitWayRefusesEndNodesAndForeignNodes() throws Exception {
		Way w = openLine("waterway", "drain", 4);
		long loose = node(6.0, 53.0);
		assertTrue(assertThrows(Exception.class, () -> new SplitWay().handle(args("id", w.getUniqueId(),
				"at_node_ids", Arrays.asList(w.getNode(0).getUniqueId())))).getMessage().contains("end node"));
		assertTrue(assertThrows(Exception.class, () -> new SplitWay().handle(args("id", w.getUniqueId(),
				"at_node_ids", Arrays.asList(loose)))).getMessage().contains("not a node of way"));
		Way ring = square();
		assertTrue(assertThrows(Exception.class, () -> new SplitWay().handle(args("id", ring.getUniqueId(),
				"at_node_ids", Arrays.asList(ring.getNode(1).getUniqueId())))).getMessage().contains("at least two"));
	}

	@Test
	void reverseWayFlipsNodeOrderInOneStep() throws Exception {
		Way w = openLine("waterway", "drain", 4);
		List<Node> before = new ArrayList<>(w.getNodes());
		int commands = UndoRedoHandler.getInstance().getUndoCommands().size();

		JsonNode r = JSON.readTree(new ReverseWay().handle(args("ids", Arrays.asList(w.getUniqueId()))));
		assertEquals(1, r.path("reversed_count").asInt(), r.toString());
		assertEquals(commands + 1, UndoRedoHandler.getInstance().getUndoCommands().size());
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i), w.getNode(before.size() - 1 - i), "node order must be mirrored");
		}
		assertEquals("drain", w.get("waterway"), "tags are untouched");
	}

	@Test
	void reverseWayRefusesDirectionDependentTags() throws Exception {
		Way plain = openLine("waterway", "ditch", 3);
		Way oneway = openLine("highway", "residential", 3);
		new ModifyTags().handle(args("element_type", "way", "element_id", oneway.getUniqueId(),
				"tags", Map.of("oneway", "yes")));
		List<Node> plainBefore = new ArrayList<>(plain.getNodes());

		// all-or-nothing by default: neither way is touched
		assertTrue(assertThrows(Exception.class, () -> new ReverseWay().handle(args("ids",
				Arrays.asList(plain.getUniqueId(), oneway.getUniqueId())))).getMessage().contains("depend on the direction"));
		assertEquals(plainBefore, plain.getNodes(), "nothing may change when the call is refused");

		JsonNode r = JSON.readTree(new ReverseWay().handle(args("ids",
				Arrays.asList(plain.getUniqueId(), oneway.getUniqueId()), "skip_irreversible", true)));
		assertEquals(1, r.path("reversed_count").asInt(), r.toString());
		assertEquals(1, r.path("skipped_count").asInt(), r.toString());
		assertEquals(oneway.getUniqueId(), r.path("skipped").get(0).path("id").asLong());
		assertEquals("yes", oneway.get("oneway"));
	}

	@Test
	void insertNodeInWayPicksTheNearestSegment() throws Exception {
		Way w = square();
		int nodesBefore = w.getNodesCount();
		// about 0.35 m north of the southern edge, halfway along it
		long entrance = node(5.0005, 52.0000031);
		new ModifyTags().handle(args("element_type", "node", "element_id", entrance, "tags", Map.of("entrance", "main")));

		JsonNode r = JSON.readTree(new InsertNodeInWay().handle(args("way_id", w.getUniqueId(), "node_id", entrance)));
		assertEquals(1, r.path("index").asInt(), r.toString());
		assertTrue(r.path("distance_m").asDouble() < 1.0, r.toString());
		assertTrue(r.path("node_is_tagged").asBoolean());
		assertFalse(r.path("snapped").asBoolean());
		assertEquals(nodesBefore + 1, w.getNodesCount());
		Node n = (Node) ds.getPrimitiveById(new SimplePrimitiveId(entrance, OsmPrimitiveType.NODE));
		assertEquals(n, w.getNode(1));
		assertTrue(w.isClosed(), "a ring stays closed");
	}

	@Test
	void insertNodeInWayRefusesFarNodesAndCanSnap() throws Exception {
		Way w = square();
		long far = node(5.0005, 52.0002);
		Exception e = assertThrows(Exception.class,
				() -> new InsertNodeInWay().handle(args("way_id", w.getUniqueId(), "node_id", far)));
		assertTrue(e.getMessage().contains("max_distance_m"), e.getMessage());
		assertFalse(w.containsNode((Node) ds.getPrimitiveById(new SimplePrimitiveId(far, OsmPrimitiveType.NODE))));

		long near = node(5.0007, 52.0000031);
		JsonNode r = JSON.readTree(new InsertNodeInWay().handle(args("way_id", w.getUniqueId(), "node_id", near,
				"snap_m", 1.0)));
		assertTrue(r.path("snapped").asBoolean(), r.toString());
		Node n = (Node) ds.getPrimitiveById(new SimplePrimitiveId(near, OsmPrimitiveType.NODE));
		assertEquals(52.0, n.lat(), 1e-9, "snapped onto the southern edge");
		assertTrue(w.containsNode(n));
	}

	@Test
	void insertNodeInWayRejectsANodeItAlreadyHas() throws Exception {
		Way w = square();
		Exception e = assertThrows(Exception.class, () -> new InsertNodeInWay().handle(
				args("way_id", w.getUniqueId(), "node_id", w.getNode(1).getUniqueId())));
		assertTrue(e.getMessage().contains("already a node of way"), e.getMessage());
	}

	@Test
	void updateRelationMembersReportsThePreviousCount() throws Exception {
		Way w = square();
		long extra = node(5.002, 52.002);
		String rid = new CreateRelation().handle(args("tags", Map.of("type", "multipolygon"),
				"members", Arrays.asList(Map.of("type", "way", "ref", w.getUniqueId(), "role", "outer"))));
		long relId = Long.parseLong(rid.replaceAll("[^-0-9]", ""));
		String out = new UpdateRelation().handle(args("id", relId, "members", Arrays.asList(
				Map.of("type", "way", "ref", w.getUniqueId(), "role", "outer"),
				Map.of("type", "node", "ref", extra, "role", "label"))));
		assertTrue(out.contains("now has 2 members (was 1)"), out);
	}

	@Test
	void searchWithRegexMatchesPartOfAValue() throws Exception {
		Way a = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", a.getUniqueId(),
				"tags", Map.of("building", "yes", "source:date", "2014-03-24")));
		Way b = way("building", "house", node(5.01, 52.01), node(5.011, 52.01), node(5.011, 52.011), node(5.01, 52.011));
		new ModifyTags().handle(args("element_type", "way", "element_id", b.getUniqueId(),
				"tags", Map.of("source:date", "2025-11-10")));

		// key=value is exact and has no wildcard, so a partial value finds nothing
		assertEquals(0, JSON.readTree(new SearchTool().handle(args("query", "\"source:date\"=2014")))
				.path("total_matches").asInt(), "key=value must stay an exact match");
		assertEquals(0, JSON.readTree(new SearchTool().handle(args("query", "\"source:date\"=2014.*")))
				.path("total_matches").asInt(), "a regex is only a regex with regex=true");

		JsonNode r = JSON.readTree(new SearchTool().handle(args("query", "\"source:date\"=2014.*", "regex", true)));
		assertEquals(1, r.path("total_matches").asInt(), r.toString());
		assertEquals(a.getUniqueId(), r.path("elements").get(0).path("id").asLong());

		JsonNode both = JSON.readTree(new SearchTool().handle(args("query", "\"source:date\"=20[0-9]{2}-.*", "regex", true)));
		assertEquals(2, both.path("total_matches").asInt(), both.toString());
		// the regex has to match the whole value
		assertEquals(0, JSON.readTree(new SearchTool().handle(args("query", "\"source:date\"=03", "regex", true)))
				.path("total_matches").asInt());
	}

	@Test
	void caseSensitiveOnlyAppliesToRegexMatching() throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(),
				"tags", Map.of("building", "yes", "name", "Buterblom")));

		// a regex ignores case by default, and case_sensitive turns that off
		assertEquals(1, JSON.readTree(new SearchTool().handle(args("query", "name=b.*BLOM", "regex", true)))
				.path("total_matches").asInt());
		assertEquals(0, JSON.readTree(new SearchTool().handle(args("query", "name=b.*BLOM", "regex", true,
				"case_sensitive", true))).path("total_matches").asInt());
		assertEquals(1, JSON.readTree(new SearchTool().handle(args("query", "name=B.*blom", "regex", true,
				"case_sensitive", true))).path("total_matches").asInt());
		// without regex the match is exact and case sensitive either way
		assertEquals(0, JSON.readTree(new SearchTool().handle(args("query", "name=buterblom")))
				.path("total_matches").asInt());
		assertEquals(1, JSON.readTree(new SearchTool().handle(args("query", "name=Buterblom")))
				.path("total_matches").asInt());
	}

	@Test
	void searchCanReturnWayGeometry() throws Exception {
		Way w = square();
		new ModifyTags().handle(args("element_type", "way", "element_id", w.getUniqueId(), "tags", Map.of("building", "yes")));

		JsonNode plain = JSON.readTree(new SearchTool().handle(args("query", "building=yes")));
		assertTrue(plain.path("elements").get(0).path("nodes").isMissingNode(), "geometry is opt-in");

		JsonNode r = JSON.readTree(new SearchTool().handle(args("query", "building=yes", "include_geometry", true)));
		JsonNode nodes = r.path("elements").get(0).path("nodes");
		assertEquals(w.getNodesCount(), nodes.size(), r.toString());
		assertEquals(w.getNode(0).getUniqueId(), nodes.get(0).path("id").asLong());
		assertEquals(52.0, nodes.get(0).path("lat").asDouble(), 1e-9);
		assertEquals(5.0, nodes.get(0).path("lon").asDouble(), 1e-9);

		// 'fields' must not drop geometry that was asked for explicitly
		JsonNode narrow = JSON.readTree(new SearchTool().handle(args("query", "building=yes",
				"include_geometry", true, "fields", Arrays.asList("id"))));
		JsonNode el = narrow.path("elements").get(0);
		assertTrue(el.path("tags").isMissingNode(), "fields still narrows the rest");
		assertEquals(w.getNodesCount(), el.path("nodes").size(), narrow.toString());
	}

	@Test
	void searchGeometryLeavesNodesAndRelationsAlone() throws Exception {
		Way w = square();
		String rid = new CreateRelation().handle(args("tags", Map.of("type", "multipolygon"),
				"members", Arrays.asList(Map.of("type", "way", "ref", w.getUniqueId(), "role", "outer"))));
		assertNotNull(rid);
		JsonNode r = JSON.readTree(new SearchTool().handle(args("query", "type:node", "include_geometry", true,
				"max_results", 1)));
		JsonNode n = r.path("elements").get(0);
		assertTrue(n.path("nodes").isMissingNode(), "a node needs no node list");
		Node hit = (Node) ds.getPrimitiveById(new SimplePrimitiveId(n.path("id").asLong(), OsmPrimitiveType.NODE));
		assertEquals(hit.lat(), n.path("lat").asDouble(), 1e-9, "a node already carries its coordinates");
		assertEquals(hit.lon(), n.path("lon").asDouble(), 1e-9);
		JsonNode rel = JSON.readTree(new SearchTool().handle(args("query", "type:relation", "include_geometry", true)));
		assertTrue(rel.path("elements").get(0).path("nodes").isMissingNode(), "relations are not expanded");
	}

	private static int idx(String name) {
		List<Layer> ls = MainApplication.getLayerManager().getLayers();
		for (int i = 0; i < ls.size(); i++) {
			if (ls.get(i).getName().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void moveLayerReordersTheStack() throws Exception {
		OsmDataLayer alpha = new OsmDataLayer(new DataSet(), "alpha", null);
		OsmDataLayer beta = new OsmDataLayer(new DataSet(), "beta", null);
		MainApplication.getLayerManager().addLayer(alpha);
		MainApplication.getLayerManager().addLayer(beta);
		try {
			int count = MainApplication.getLayerManager().getLayers().size();
			assertEquals(3, count);

			JsonNode r = JSON.readTree(new MoveLayer().handle(args("layer", "beta", "direction", "top")));
			assertEquals(0, r.path("to_index").asInt());
			assertEquals(0, idx("beta"));
			// the result lists every layer with its index
			assertEquals(count, r.path("layers").size());
			assertEquals("beta", r.path("layers").get(0).path("name").asText());

			new MoveLayer().handle(args("layer", "beta", "direction", "bottom"));
			assertEquals(count - 1, idx("beta"));

			// one step back up
			new MoveLayer().handle(args("layer", "beta", "direction", "up"));
			assertEquals(count - 2, idx("beta"));

			new MoveLayer().handle(args("layer", "alpha", "above", "beta"));
			assertEquals(idx("beta") - 1, idx("alpha"));

			new MoveLayer().handle(args("layer", "alpha", "below", "beta"));
			assertEquals(idx("beta") + 1, idx("alpha"));

			// an absolute position, and out-of-range values clamp instead of failing
			new MoveLayer().handle(args("layer", "alpha", "position", 0));
			assertEquals(0, idx("alpha"));
			JsonNode clamped = JSON.readTree(new MoveLayer().handle(args("layer", "alpha", "position", 99)));
			assertEquals(count - 1, clamped.path("to_index").asInt());

			// moving to where it already is is reported, not an error
			JsonNode same = JSON.readTree(new MoveLayer().handle(args("layer", "alpha", "direction", "bottom")));
			assertTrue(same.path("unchanged").asBoolean());
		} finally {
			MainApplication.getLayerManager().removeLayer(alpha);
			MainApplication.getLayerManager().removeLayer(beta);
		}
	}

	@Test
	void moveLayerRejectsAmbiguousAndConflictingArguments() throws Exception {
		OsmDataLayer alpha = new OsmDataLayer(new DataSet(), "alpha", null);
		MainApplication.getLayerManager().addLayer(alpha);
		try {
			assertThrows(Exception.class, () -> new MoveLayer().handle(args("layer", "alpha")));
			assertThrows(Exception.class,
					() -> new MoveLayer().handle(args("layer", "alpha", "direction", "up", "position", 0)));
			assertThrows(Exception.class,
					() -> new MoveLayer().handle(args("layer", "alpha", "direction", "sideways")));
			assertThrows(Exception.class, () -> new MoveLayer().handle(args("layer", "alpha", "above", "alpha")));
			assertThrows(Exception.class, () -> new MoveLayer().handle(args("layer", "nosuchlayer", "direction", "up")));
		} finally {
			MainApplication.getLayerManager().removeLayer(alpha);
		}
	}

}
