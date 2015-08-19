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

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class TableTest {

  @Test
  public void insertRowShouldWorkWithTheCorrectAmountOfArguments() throws Exception {
    Table table = new Table("A", "B");
    try {
      for (int i = 0; i < 100; i++) {
        table.insertRow("1", "2");
      }
    } catch (Exception unexpected) {
      Assert.fail();
    }
  }

  @Test
  public void constructorShouldThrowAnExceptionIfThereAreNoArguments() throws Exception {
    try {
      new Table();
      Assert.fail("expected an IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void constructorShouldThrowAnExceptionIfThereAreTooManyArguments() throws Exception {
    try {
      new Table("A", "B", "C", "D", "E", "F");
      Assert.fail("expected an IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void insertRowShouldThrowAnExceptionWithTooFewArguments() throws Exception {
    Table table = new Table("A", "B");
    try {
      table.insertRow("1");
      Assert.fail("expected an IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void insertRowShouldThrowAnExceptionWithTooManyArguments() throws Exception {
    Table table = new Table("A", "B");
    try {
      table.insertRow("1", "2", "3");
      Assert.fail("expected an IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void distributeShouldFailOnEmptyArray() throws Exception {
    int[] emptyArray = new int[0];
    try {
      Table.distribute(100, emptyArray);
      Assert.fail("expected an IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testDistribute() throws Exception {
    int[] array = {0, 0};
    Table.distribute(100, array);
    Assert.assertEquals(50, array[0]);
    Assert.assertEquals(50, array[1]);
    Table.distribute(-200, array);
    Assert.assertEquals(-50, array[0]);
    Assert.assertEquals(-50, array[1]);
    Table.distribute(0, array);
    Assert.assertEquals(-50, array[0]);
    Assert.assertEquals(-50, array[1]);
    Table.distribute(1, array);
    Assert.assertEquals(-49, array[0]);
    Assert.assertEquals(-50, array[1]);
    // Test the examples given in the Javadoc.
    int[] first = {2, 3, 4};
    Table.distribute(3, first);
    final int[] firstExpected = {3, 4, 5};
    Assert.assertTrue(Arrays.equals(firstExpected, first));
    int[] second = {5, 10};
    Table.distribute(-8, second);
    int[] secondExpected = {1, 6};
    Assert.assertTrue(Arrays.equals(secondExpected, second));
    int[] third = {2, 3};
    Table.distribute(3, third);
    final int[] thirdExpected = {4, 4};
    Assert.assertTrue(Arrays.equals(thirdExpected, third));
    int[] fourth = {5, 10, 15};
    Table.distribute(-8, fourth);
    int[] fourthExpected = {2, 7, 13};
    Assert.assertTrue(Arrays.equals(fourthExpected, fourth));
  }

}
