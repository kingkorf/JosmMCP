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

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import org.openstreetmap.josm.gui.preferences.DefaultTabPreferenceSetting;
import org.openstreetmap.josm.gui.preferences.PreferenceTabbedPane;
import org.openstreetmap.josm.plugins.josmmcp.Prefs;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;

/**
 * Preferences tab: bind address, port, access token, read-only mode and output limit.
 */
public class JosmMCPPreferences extends DefaultTabPreferenceSetting {
	private final JTextField host = new JTextField(20);
	private final JTextField port = new JTextField(6);
	private final JPasswordField token = new JPasswordField(30);
	private final JCheckBox readOnly = new JCheckBox(tr("Read-only mode (block all tools that modify data or files)"));
	private final JTextField maxOutput = new JTextField(8);

	public JosmMCPPreferences() {
		super("josmmcp", tr("JosmMCP"), tr("Model Context Protocol server for AI assistants"));
	}

	@Override
	public void addGui(PreferenceTabbedPane gui) {
		JPanel p = new JPanel(new GridBagLayout());
		host.setText(Prefs.host());
		port.setText(String.valueOf(Prefs.port()));
		token.setText(Prefs.token());
		readOnly.setSelected(Prefs.readOnly());
		maxOutput.setText(String.valueOf(Config.getPref().getInt(Prefs.MAX_OUTPUT_CHARS, Prefs.DEFAULT_MAX_OUTPUT_CHARS)));

		p.add(new JLabel(tr("Bind address:")), GBC.std().insets(5, 5, 5, 0));
		p.add(host, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel(tr("Port:")), GBC.std().insets(5, 5, 5, 0));
		p.add(port, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Address and port take effect after restarting JOSM. Keep 127.0.0.1 unless you know what you are doing: the server has no user accounts.") + "</i></html>"), GBC.eol().insets(5, 0, 5, 10));

		p.add(new JLabel(tr("Access token:")), GBC.std().insets(5, 5, 5, 0));
		p.add(token, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Optional. When set, clients must send it as ''Authorization: Bearer <token>''. Applies immediately.") + "</i></html>"), GBC.eol().insets(5, 0, 5, 10));

		p.add(readOnly, GBC.eol().insets(5, 5, 5, 10));

		p.add(new JLabel(tr("Maximum characters per tool result:")), GBC.std().insets(5, 5, 5, 0));
		p.add(maxOutput, GBC.eol().insets(0, 5, 5, 0));
		p.add(new JLabel("<html><i>" + tr("Longer results are truncated with a notice. 0 disables the limit.") + "</i></html>"), GBC.eol().insets(5, 0, 5, 10));

		p.add(new JLabel(tr("Endpoint: http://{0}:{1}/mcp", Prefs.host(), Prefs.port())), GBC.eol().insets(5, 10, 5, 5));
		p.add(new JLabel(""), GBC.eol().fill(GBC.BOTH));
		createPreferenceTabWithScrollPane(gui, p);
	}

	@Override
	public boolean ok() {
		Config.getPref().put(Prefs.HOST, host.getText().trim().isEmpty() ? Prefs.DEFAULT_HOST : host.getText().trim());
		try {
			int portValue = Integer.parseInt(port.getText().trim());
			if (portValue < 1 || portValue > 65535) {
				throw new NumberFormatException();
			}
			Config.getPref().putInt(Prefs.PORT, portValue);
		} catch (NumberFormatException e) {
			Config.getPref().putInt(Prefs.PORT, Prefs.DEFAULT_PORT);
		}
		Config.getPref().put(Prefs.TOKEN, new String(token.getPassword()).trim());
		Config.getPref().putBoolean(Prefs.READ_ONLY, readOnly.isSelected());
		try {
			Config.getPref().putInt(Prefs.MAX_OUTPUT_CHARS, Integer.parseInt(maxOutput.getText().trim()));
		} catch (NumberFormatException e) {
			Config.getPref().putInt(Prefs.MAX_OUTPUT_CHARS, Prefs.DEFAULT_MAX_OUTPUT_CHARS);
		}
		return false; // no restart required for the settings that apply immediately
	}
}
