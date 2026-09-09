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
package org.openstreetmap.josm.plugins.josmmcp.server;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.josmmcp.tools.BaseTool;
import org.openstreetmap.josm.plugins.josmmcp.tools.CaptureMapView;
import org.openstreetmap.josm.plugins.josmmcp.tools.CreateNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.CreateRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.CreateWay;
import org.openstreetmap.josm.plugins.josmmcp.tools.DownloadArea;
import org.openstreetmap.josm.plugins.josmmcp.tools.DownloadIncomplete;
import org.openstreetmap.josm.plugins.josmmcp.tools.ImageryTools;
import org.openstreetmap.josm.plugins.josmmcp.tools.PendingChangesSummary;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadElements;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadHistory;
import org.openstreetmap.josm.plugins.josmmcp.tools.DeleteNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.DeleteRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.DeleteWay;
import org.openstreetmap.josm.plugins.josmmcp.tools.GetUserSelection;
import org.openstreetmap.josm.plugins.josmmcp.tools.ModifyTags;
import org.openstreetmap.josm.plugins.josmmcp.tools.ModifyTagsBatch;
import org.openstreetmap.josm.plugins.josmmcp.tools.OpenFile;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReadWay;
import org.openstreetmap.josm.plugins.josmmcp.tools.ReplaceGeometry;
import org.openstreetmap.josm.plugins.josmmcp.tools.RevertToServer;
import org.openstreetmap.josm.plugins.josmmcp.tools.SaveLayer;
import org.openstreetmap.josm.plugins.josmmcp.tools.SearchTool;
import org.openstreetmap.josm.plugins.josmmcp.tools.SelectElements;
import org.openstreetmap.josm.plugins.josmmcp.tools.SetLayerVisibility;
import org.openstreetmap.josm.plugins.josmmcp.tools.StateTool;
import org.openstreetmap.josm.plugins.josmmcp.tools.UndoRedoTool;
import org.openstreetmap.josm.plugins.josmmcp.tools.UpdateNode;
import org.openstreetmap.josm.plugins.josmmcp.tools.UpdateRelation;
import org.openstreetmap.josm.plugins.josmmcp.tools.UpdateWayNodes;
import org.openstreetmap.josm.plugins.josmmcp.tools.ValidateTool;

/** The tools the server exposes, grouped by purpose. */
public final class ToolRegistry {
	private ToolRegistry() {
	}

	public static List<BaseTool> all() {
		List<BaseTool> t = new ArrayList<>();
		// Inspect
		t.add(new StateTool());
		t.add(new GetUserSelection());
		t.add(new SearchTool());
		t.add(new SelectElements());
		t.add(new CaptureMapView());
		t.add(new SetLayerVisibility());
		t.add(new ValidateTool());
		t.add(new ReadElements());
		t.add(new ReadHistory());
		t.add(new PendingChangesSummary());
		// Layers and downloads
		t.add(new DownloadArea());
		t.add(new DownloadIncomplete());
		t.add(new ImageryTools(ImageryTools.Mode.LIST));
		t.add(new ImageryTools(ImageryTools.Mode.ADD));
		t.add(new ImageryTools(ImageryTools.Mode.REMOVE));
		// Nodes
		t.add(new CreateNode());
		t.add(new ReadNode());
		t.add(new UpdateNode());
		t.add(new DeleteNode());
		// Ways
		t.add(new CreateWay());
		t.add(new ReadWay());
		t.add(new UpdateWayNodes());
		t.add(new ReplaceGeometry());
		t.add(new DeleteWay());
		// Relations
		t.add(new CreateRelation());
		t.add(new ReadRelation());
		t.add(new UpdateRelation());
		t.add(new DeleteRelation());
		// Tags
		t.add(new ModifyTags());
		t.add(new ModifyTagsBatch());
		// History and files
		t.add(new UndoRedoTool(UndoRedoTool.Mode.UNDO));
		t.add(new UndoRedoTool(UndoRedoTool.Mode.REDO));
		t.add(new UndoRedoTool(UndoRedoTool.Mode.LIST));
		t.add(new RevertToServer());
		t.add(new SaveLayer());
		t.add(new OpenFile());
		return t;
	}
}
