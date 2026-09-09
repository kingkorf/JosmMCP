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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.openstreetmap.josm.plugins.josmmcp.Prefs;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Appends one line per write-tool call to {@code josmmcp-audit.log} in JOSM's user data
 * directory, so a session can be reviewed afterwards.
 */
public final class AuditLog {
	private static final int MAX_FIELD = 600;

	private AuditLog() {
	}

	public static File file() {
		return new File(Config.getDirs().getUserDataDirectory(true), "josmmcp-audit.log");
	}

	public static synchronized void record(String tool, Object args, String outcome, String detail) {
		if (!Prefs.audit()) {
			return;
		}
		String line = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()) + "\t" + tool + "\t"
				+ outcome + "\t" + clip(String.valueOf(args)) + "\t" + clip(detail) + System.lineSeparator();
		try {
			Files.writeString(file().toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			Logging.warn("JosmMCP audit log not written: " + e);
		}
	}

	private static String clip(String s) {
		if (s == null) {
			return "";
		}
		String t = s.replace('\n', ' ').replace('\t', ' ');
		return t.length() > MAX_FIELD ? t.substring(0, MAX_FIELD) + "..." : t;
	}
}
