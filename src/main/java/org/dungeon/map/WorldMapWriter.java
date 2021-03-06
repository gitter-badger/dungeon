/*
 * Copyright (C) 2015 Bernardo Sulzbach
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dungeon.map;

import org.dungeon.game.DungeonStringBuilder;
import org.dungeon.io.Writer;

import org.jetbrains.annotations.NotNull;

public final class WorldMapWriter {

  private WorldMapWriter() {
    throw new AssertionError();
  }

  /**
   * Writes a WorldMap to the screen. This erases all the content currently on the screen.
   *
   * @param map a WorldMap, not null
   */
  public static void writeMap(@NotNull WorldMap map) {
    DungeonStringBuilder dungeonStringBuilder = new DungeonStringBuilder();
    WorldMapSymbol[][] worldMapSymbolMatrix = map.getSymbolMatrix();
    for (int i = 0; i < worldMapSymbolMatrix.length; i++) {
      for (WorldMapSymbol symbol : worldMapSymbolMatrix[i]) {
        // OK as setColor verifies if the color change is necessary (does not replace a color by itself).
        dungeonStringBuilder.setColor(symbol.getColor());
        dungeonStringBuilder.append(symbol.getCharacterAsString());
      }
      if (i < worldMapSymbolMatrix.length - 1) {
        dungeonStringBuilder.append("\n");
      }
    }
    Writer.write(dungeonStringBuilder);
  }

}
