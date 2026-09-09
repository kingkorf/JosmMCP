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
package org.openstreetmap.josm.plugins.josmmcp.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.plugins.josmmcp.Prefs;
import org.openstreetmap.josm.plugins.josmmcp.server.AuditLog;
import org.openstreetmap.josm.plugins.josmmcp.tools.Tool.Category;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;

/**
 * Preferences tab: connection, access, permissions per tool group, confirmation and audit log.
 */
public class JosmMCPPreferences extends DefaultTabPreferenceSetting {
	private final JTextField host = new JTextField(20);
	private final JTextField port = new JTextField(6);
	private final JPasswordField token = new JPasswordField(30);
	private final JCheckBox readOnly = new JCheckBox(tr("Read-only mode (block every tool that modifies data or files)"));
	private final JTextField maxOutput = new JTextField(8);
	private final JCheckBox confirm = new JCheckBox(tr("Ask before deleting objects or replacing geometry"));
	private final JTextField confirmTimeout = new JTextField(4);
	private final JCheckBox audit = new JCheckBox(tr("Write every modifying tool call to the audit log"));
	private final Map<Category, JCheckBox> allow = new EnumMap<>(Category.class);

	public JosmMCPPreferences() {
		super("josmmcp", tr("JosmMCP"), tr("Model Context Protocol server for AI assistants"));
	}

	private static String label(Category c) {
		switch (c) {
		case VIEW: return tr("Change the view: selection, zoom, layer visibility");
		case TAGS: return tr("Edit tags");
		case GEOMETRY: return tr("Create objects, move nodes, change geometry and relation members");
		case DELETE: return tr("Delete objects");
		case HISTORY: return tr("Undo, redo, revert to server");
		case FILES: return tr("Save and open files");
		case DOWNLOAD: return tr("Download data, add or remove layers");
		default: return tr("Read data");
		}
	}

	@Override
	public void addGui(PreferenceTabbedPane gui) {
		JPanel p = new JPanel(new GridBagLayout());
		host.setText(Prefs.host());
		port.setText(String.valueOf(Prefs.port()));
		token.setText(Prefs.token());
		readOnly.setSelected(Prefs.readOnly());
		maxOutput.setText(String.valueOf(Config.getPref().getInt(Prefs.MAX_OUTPUT_CHARS, Prefs.DEFAULT_MAX_OUTPUT_CHARS)));
		confirm.setSelected(Prefs.confirmDestructive());
		confirmTimeout.setText(String.valueOf(Prefs.confirmTimeoutSeconds()));
		audit.setSelected(Prefs.audit());

		p.add(new JLabel("<html><b>" + tr("Connection") + "</b></html>"), GBC.eol().insets(5, 5, 5, 0));
		p.add(new JLabel(tr("Bind address:")), GBC.std().insets(15, 5, 5, 0));
		p.add(host, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel(tr("Port:")), GBC.std().insets(15, 5, 5, 0));
		p.add(port, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Address and port take effect after restarting JOSM. Keep 127.0.0.1 unless you know what you are doing: the server has no user accounts.") + "</i></html>"), GBC.eol().insets(15, 0, 5, 5));
		p.add(new JLabel(tr("Access token:")), GBC.std().insets(15, 5, 5, 0));
		p.add(token, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Optional. When set, clients must send it as ''Authorization: Bearer <token>''. Applies immediately.") + "</i></html>"), GBC.eol().insets(15, 0, 5, 10));

		p.add(new JLabel("<html><b>" + tr("Permissions") + "</b></html>"), GBC.eol().insets(5, 5, 5, 0));
		p.add(readOnly, GBC.eol().insets(15, 2, 5, 2));
		p.add(new JLabel(tr("Allowed tool groups (with read-only off):")), GBC.eol().insets(15, 5, 5, 0));
		for (Category c : Category.values()) {
			if (c == Category.READ) {
				continue;
			}
			JCheckBox cb = new JCheckBox(label(c));
			cb.setSelected(Prefs.allowed(c));
			allow.put(c, cb);
			p.add(cb, GBC.eol().insets(30, 0, 5, 0));
		}
		p.add(confirm, GBC.std().insets(15, 8, 5, 0));
		p.add(new JLabel(tr("timeout (s):")), GBC.std().insets(5, 8, 5, 0));
		p.add(confirmTimeout, GBC.eol().insets(0, 8, 5, 0));
		p.add(new JLabel("<html><i>" + tr("A dialog in JOSM asks you to allow each delete or geometry replacement; no answer within the timeout means no.") + "</i></html>"), GBC.eol().insets(15, 0, 5, 10));

		p.add(new JLabel("<html><b>" + tr("Logging and output") + "</b></html>"), GBC.eol().insets(5, 5, 5, 0));
		p.add(audit, GBC.eol().insets(15, 2, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Log file: {0}", AuditLog.file().getAbsolutePath()) + "</i></html>"), GBC.eol().insets(30, 0, 5, 5));
		p.add(new JLabel(tr("Maximum characters per tool result:")), GBC.std().insets(15, 5, 5, 0));
		p.add(maxOutput, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Longer results are truncated with a notice. 0 disables the limit.") + "</i></html>"), GBC.eol().insets(15, 0, 5, 10));

		p.add(new JLabel(tr("Endpoint: http://{0}:{1}/mcp", Prefs.host(), Prefs.port())), GBC.eol().insets(5, 10, 5, 5));
		p.add(new JLabel(""), GBC.eol().fill(GBC.BOTH));
		createPreferenceTabWithScrollPane(gui, p);
	}

	@Override
	public boolean ok() {
		Config.getPref().put(Prefs.HOST, host.getText().trim().isEmpty() ? Prefs.DEFAULT_HOST : host.getText().trim());
		Config.getPref().putInt(Prefs.PORT, parseInt(port.getText(), Prefs.DEFAULT_PORT, 1, 65535));
		Config.getPref().put(Prefs.TOKEN, new String(token.getPassword()).trim());
		Config.getPref().putBoolean(Prefs.READ_ONLY, readOnly.isSelected());
		Config.getPref().putInt(Prefs.MAX_OUTPUT_CHARS, parseInt(maxOutput.getText(), Prefs.DEFAULT_MAX_OUTPUT_CHARS, 0, Integer.MAX_VALUE));
		Config.getPref().putBoolean(Prefs.CONFIRM_DESTRUCTIVE, confirm.isSelected());
		Config.getPref().putInt(Prefs.CONFIRM_TIMEOUT, parseInt(confirmTimeout.getText(), Prefs.DEFAULT_CONFIRM_TIMEOUT, 5, 3600));
		Config.getPref().putBoolean(Prefs.AUDIT, audit.isSelected());
		for (Map.Entry<Category, JCheckBox> e : allow.entrySet()) {
			Config.getPref().putBoolean(Prefs.ALLOW_PREFIX + e.getKey().prefKey, e.getValue().isSelected());
		}
		return false;
	}

	private static int parseInt(String text, int def, int min, int max) {
		try {
			int v = Integer.parseInt(text.trim());
			return v < min || v > max ? def : v;
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
