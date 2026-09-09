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

import javax.swing.JOptionPane;
import javax.swing.Timer;

import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.josmmcp.Prefs;

/**
 * Asks the mapper to allow a destructive tool call. Must be called on the EDT. Denies when
 * the dialog is dismissed or times out.
 */
public final class ConfirmDialog {
	private ConfirmDialog() {
	}

	/**
	 * @return true when the user clicked Allow within the configured timeout
	 */
	public static boolean ask(String toolName, String what) {
		int timeout = Prefs.confirmTimeoutSeconds();
		String allow = tr("Allow");
		String deny = tr("Deny");
		ExtendedDialog dialog = new ExtendedDialog(MainApplication.getMainFrame(), tr("JosmMCP: allow this change?"),
				new String[] {allow, deny}, true);
		dialog.setButtonIcons("ok", "cancel");
		dialog.setDefaultButton(2);
		dialog.setIcon(JOptionPane.WARNING_MESSAGE);
		dialog.setContent("<html><b>" + tr("The connected assistant wants to run ''{0}''.", toolName) + "</b><br><br>"
				+ escape(what) + "<br><br><i>"
				+ tr("Denied automatically after {0} seconds. You can switch this dialog off in the JosmMCP preferences.", timeout)
				+ "</i></html>");
		Timer timer = new Timer(timeout * 1000, e -> dialog.setVisible(false));
		timer.setRepeats(false);
		timer.start();
		dialog.showDialog();
		timer.stop();
		return dialog.getValue() == 1;
	}

	private static String escape(String s) {
		if (s == null) {
			return "";
		}
		String t = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return t.length() > 800 ? t.substring(0, 800) + "..." : t;
	}
}
