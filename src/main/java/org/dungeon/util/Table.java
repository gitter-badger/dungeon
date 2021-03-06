/*
 * Copyright (C) 2014 Bernardo Sulzbach
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

package org.dungeon.util;

import org.dungeon.gui.GameWindow;
import org.dungeon.io.DungeonLogger;
import org.dungeon.io.Writer;

import java.util.ArrayList;

/**
 * Table class that provides table functionality for printing in-game tables.
 */
public class Table {

  private static final char HORIZONTAL_BAR = '-';
  private static final char VERTICAL_BAR = '|';

  /**
   * The content of the Table.
   */
  private final ArrayList<Column> columns;

  /**
   * A List of Integers representing which rows should be preceded by horizontal separators.
   *
   * <p>Repeated integers make multiple horizontal separators.
   */
  private CounterMap<Integer> separators;

  /**
   * Constructs a Table using the provided Strings as column headers.
   *
   * @param headers the headers
   */
  public Table(String... headers) {
    columns = new ArrayList<Column>(headers.length);
    for (String header : headers) {
      columns.add(new Column(header));
    }
  }

  /**
   * Creates a string of repeated characters.
   */
  private static String makeRepeatedCharacterString(int repetitions, char character) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < repetitions; i++) {
      builder.append(character);
    }
    return builder.toString();
  }

  /**
   * Appends a row to a StringBuilder.
   *
   * @param stringBuilder the StringBuilder object
   * @param columnWidths the widths of the columns of the table
   * @param values the values of the row
   */
  private static void appendRow(StringBuilder stringBuilder, int[] columnWidths, String... values) {
    String currentValue;
    for (int i = 0; i < values.length; i++) {
      int columnWidth = columnWidths[i];
      currentValue = values[i];
      if (currentValue.length() > columnWidth) {
        stringBuilder.append(currentValue.substring(0, columnWidth - 3)).append("...");
      } else {
        stringBuilder.append(currentValue);
        int extraSpaces = columnWidth - currentValue.length();
        for (int j = 0; j < extraSpaces; j++) {
          stringBuilder.append(" ");
        }
      }
      if (i < values.length - 1) {
        stringBuilder.append(VERTICAL_BAR);
      }
    }
    stringBuilder.append('\n');
  }

  /**
   * Append a horizontal separator made up of dashes to a StringBuilder.
   *
   * @param stringBuilder the StringBuilder object
   * @param columnWidths the width of the columns of the table
   */
  private static void appendHorizontalSeparator(StringBuilder stringBuilder, int[] columnWidths, int columnCount) {
    String[] pseudoRow = new String[columnCount];
    for (int i = 0; i < columnWidths.length; i++) {
      pseudoRow[i] = makeRepeatedCharacterString(columnWidths[i], HORIZONTAL_BAR);
    }
    appendRow(stringBuilder, columnWidths, pseudoRow);
  }

  /**
   * Inserts a row of values at the end of the table.
   *
   * <p>If not enough values are supplied, the remaining columns are filled with the empty string.
   *
   * <p>If too many values are supplied, a warning is logged and the table is left unchanged.
   *
   * @param values the values to be inserted
   */
  public void insertRow(String... values) {
    int columnCount = columns.size();
    if (values.length <= columnCount) {
      for (int i = 0; i < columnCount; i++) {
        if (i < values.length) {
          columns.get(i).insertValue(values[i]);
        } else {
          columns.get(i).insertValue("");
        }
      }
    } else {
      DungeonLogger.warning("Tried to insert more values than columns.");
    }
  }

  /**
   * Inserts a horizontal separator at the last row of the Table.
   */
  public void insertSeparator() {
    if (separators == null) {
      separators = new CounterMap<Integer>();
    }
    separators.incrementCounter(getDimensions().get(0));
  }

  /**
   * Tests if the table has a specific value.
   *
   * @param value the value
   * @return true if the table contains the value, false otherwise
   */
  public boolean contains(String value) {
    for (Column column : columns) {
      if (column.contains(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a pair of the form (rows, columns) representing the table's dimensions.
   */
  public Dimensions getDimensions() {
    int columnCount = columns.size();
    if (columnCount != 0) {
      return new Dimensions(columns.get(0).size(), columnCount);
    } else {
      return new Dimensions(0, 0);
    }
  }

  /**
   * Prints the table to the game window.
   */
  public void print() {
    if (columns.isEmpty()) {
      DungeonLogger.warning("Tried to print an empty Table.");
      return;
    }

    int columnCount = columns.size();
    int[] columnWidths = calculateColumnWidths();

    int rowCount = columns.get(0).rows.size();

    StringBuilder builder = new StringBuilder(GameWindow.COLS * rowCount + 16);
    String[] currentRow = new String[columnCount];

    // Insert headers
    for (int i = 0; i < columnCount; i++) {
      currentRow[i] = columns.get(i).header;
    }
    appendRow(builder, columnWidths, currentRow);

    // A horizontal separator.
    appendHorizontalSeparator(builder, columnWidths, columnCount);

    // Insert table body.
    for (int rowIndex = 0; rowIndex < rowCount + 1; rowIndex++) {
      if (separators != null) {
        for (int remaining = separators.getCounter(rowIndex); remaining > 0; remaining--) {
          appendHorizontalSeparator(builder, columnWidths, columnCount);
        }
      }
      if (rowIndex != rowCount) {
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
          currentRow[columnIndex] = columns.get(columnIndex).rows.get(rowIndex);
        }
        appendRow(builder, columnWidths, currentRow);
      }
    }

    // Dump to the window.
    Writer.writeString(builder.toString());
  }

  private int[] calculateColumnWidths() {
    int[] widths = new int[columns.size()];
    for (int i = 0; i < widths.length; i++) {
      widths[i] = columns.get(i).widestValue;
    }
    // Subtract the number of columns to account for separators. Add one because there is not a separator at the end.
    int availableColumns = GameWindow.COLS - columns.size() + 1;
    int difference = availableColumns - DungeonMath.sum(widths);
    DungeonMath.distribute(difference, widths);
    return widths;
  }

  private class Column {
    final String header;
    final ArrayList<String> rows;
    int widestValue;

    public Column(String header) {
      rows = new ArrayList<String>();
      this.header = header;
      widestValue = header.length();
    }

    void insertValue(String value) {
      if (value == null) {
        rows.add("");
      } else {
        rows.add(value);
        int length = value.length();
        if (length > widestValue) {
          widestValue = length;
        }
      }
    }

    boolean contains(String value) {
      return rows.contains(value);
    }

    int size() {
      return rows.size();
    }

  }

}
