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
package org.openstreetmap.josm.plugins.josmmcp;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * JOSM aborts with a fatal error when a preference tab icon is missing, so make sure the
 * icons the manifest and the preferences tab refer to are really in the jar.
 */
class ResourcesTest {

	@Test
	void pluginIconIsPresent() throws IOException {
		assertReadableImage("/images/josmmcp.png");
	}

	@Test
	void preferenceTabIconIsPresent() throws IOException {
		// DefaultTabPreferenceSetting("josmmcp", ...) resolves to images/preferences/josmmcp.png
		assertReadableImage("/images/preferences/josmmcp.png");
	}

	private static void assertReadableImage(String path) throws IOException {
		try (InputStream in = ResourcesTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path + " missing from resources");
			assertNotNull(ImageIO.read(in), path + " is not a readable image");
		}
	}
}
