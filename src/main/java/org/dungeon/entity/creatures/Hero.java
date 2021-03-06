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

package org.dungeon.entity.creatures;

import static org.dungeon.date.DungeonTimeUnit.HOUR;
import static org.dungeon.date.DungeonTimeUnit.SECOND;

import org.dungeon.achievements.AchievementTracker;
import org.dungeon.commands.IssuedCommand;
import org.dungeon.date.Date;
import org.dungeon.date.Period;
import org.dungeon.entity.Entity;
import org.dungeon.entity.Visibility;
import org.dungeon.entity.items.BaseInventory;
import org.dungeon.entity.items.BookComponent;
import org.dungeon.entity.items.CreatureInventory.SimulationResult;
import org.dungeon.entity.items.FoodComponent;
import org.dungeon.entity.items.Item;
import org.dungeon.game.Direction;
import org.dungeon.game.DungeonStringBuilder;
import org.dungeon.game.Engine;
import org.dungeon.game.Game;
import org.dungeon.game.Location;
import org.dungeon.game.Name;
import org.dungeon.game.NameFactory;
import org.dungeon.game.PartOfDay;
import org.dungeon.game.Point;
import org.dungeon.game.QuantificationMode;
import org.dungeon.game.Random;
import org.dungeon.game.World;
import org.dungeon.io.Sleeper;
import org.dungeon.io.Writer;
import org.dungeon.spells.Spell;
import org.dungeon.spells.SpellData;
import org.dungeon.stats.ExplorationStatistics;
import org.dungeon.util.Constants;
import org.dungeon.util.DungeonMath;
import org.dungeon.util.Matches;
import org.dungeon.util.Messenger;
import org.dungeon.util.Percentage;
import org.dungeon.util.Utils;
import org.dungeon.util.library.Libraries;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

/**
 * Hero class that defines the creature that the player controls.
 */
public class Hero extends Creature {

  // The longest possible sleep starts at 19:00 and ends at 05:15 (takes 10 hours and 15 minutes).
  // It seems a good idea to let the Hero have one dream every 4 hours.
  private static final int DREAM_DURATION_IN_SECONDS = 4 * DungeonMath.safeCastLongToInteger(HOUR.as(SECOND));
  private static final int MILLISECONDS_TO_SLEEP_AN_HOUR = 500;
  private static final int SECONDS_TO_PICK_UP_AN_ITEM = 10;
  private static final int SECONDS_TO_DESTROY_AN_ITEM = 120;
  private static final int SECONDS_TO_EAT_AN_ITEM = 30;
  private static final int SECONDS_TO_DROP_AN_ITEM = 2;
  private static final int SECONDS_TO_UNEQUIP = 4;
  private static final int SECONDS_TO_EQUIP = 6;
  private static final int SECONDS_TO_MILK_A_CREATURE = 45;
  private static final int SECONDS_TO_READ_EQUIPPED_CLOCK = 4;
  private static final int SECONDS_TO_READ_UNEQUIPPED_CLOCK = 10;
  private static final double MAXIMUM_HEALTH_THROUGH_REST = 0.6;
  private static final Visibility ADJACENT_LOCATIONS_VISIBILITY = new Visibility(new Percentage(0.6));
  private static final int SECONDS_TO_REGENERATE_FULL_HEALTH = 30000; // 500 minutes (or 8 hours and 20 minutes).
  private static final int MILK_NUTRITION = 12;
  private final Spellcaster spellcaster = new HeroSpellcaster(this);
  private final AchievementTracker achievementTracker = new AchievementTracker();
  private final Date dateOfBirth;

  Hero(CreaturePreset preset) {
    super(preset);
    dateOfBirth = new Date(2035, 6, 4, 8, 30, 0);
  }

  public static void writeCreatureSight(List<Creature> creatures) {
    if (creatures.isEmpty()) {
      Writer.writeString("You don't see anyone here.");
    } else {
      Writer.writeString("Here you can see " + Utils.enumerateEntities(creatures) + ".");
    }
  }

  public static void writeItemSight(List<Item> items) {
    if (!items.isEmpty()) {
      Writer.writeNewLine();
      Writer.writeString("On the ground you see " + Utils.enumerateEntities(items) + ".");
    }
  }

  public Spellcaster getSpellcaster() {
    return spellcaster;
  }

  public AchievementTracker getAchievementTracker() {
    return achievementTracker;
  }

  /**
   * Increments the Hero's health by a certain amount, without exceeding its maximum health. If at the end the Hero is
   * completely healed, a messaging about this is written.
   */
  private void addHealth(int amount) {
    getHealth().incrementBy(amount);
    if (getHealth().isFull()) {
      Writer.writeString("You are completely healed.");
    }
  }

  /**
   * Rests until the hero is considered to be rested.
   */
  public void rest() {
    int maximumHealthFromRest = (int) (MAXIMUM_HEALTH_THROUGH_REST * getHealth().getMaximum());
    if (getHealth().getCurrent() >= maximumHealthFromRest) {
      Writer.writeString("You are already rested.");
    } else {
      int healthRecovered = maximumHealthFromRest - getHealth().getCurrent(); // A positive integer.
      // The fraction SECONDS_TO_REGENERATE_FULL_HEALTH / getHealth().getMaximum() may be smaller than 1.
      // Therefore, the following expression may evaluate to 0 if we do not use Math.max to secure the call to
      // Engine.rollDateAndRefresh.
      int timeResting = Math.max(1, healthRecovered * SECONDS_TO_REGENERATE_FULL_HEALTH / getHealth().getMaximum());
      Engine.rollDateAndRefresh(timeResting);
      Writer.writeString("Resting...");
      getHealth().incrementBy(healthRecovered);
      Writer.writeString("You feel rested.");
    }
  }

  /**
   * Sleep until the sun rises.
   *
   * <p>Depending on how much the Hero will sleep, this method may print a few dreams.
   */
  public void sleep() {
    int seconds;
    World world = getLocation().getWorld();
    PartOfDay pod = world.getPartOfDay();
    if (pod == PartOfDay.EVENING || pod == PartOfDay.MIDNIGHT || pod == PartOfDay.NIGHT) {
      Writer.writeString("You fall asleep.");
      seconds = PartOfDay.getSecondsToNext(world.getWorldDate(), PartOfDay.DAWN);
      // In order to increase realism, add up to 15 minutes to the time it would take to wake up exactly at dawn.
      seconds += Random.nextInteger(15 * 60 + 1);
      while (seconds > 0) {
        final int cycleDuration = Math.min(DREAM_DURATION_IN_SECONDS, seconds);
        Engine.rollDateAndRefresh(cycleDuration);
        Sleeper.sleep(MILLISECONDS_TO_SLEEP_AN_HOUR * cycleDuration / HOUR.as(SECOND));
        if (cycleDuration == DREAM_DURATION_IN_SECONDS) {
          Writer.writeString(Libraries.getDreamLibrary().next());
        }
        seconds -= cycleDuration;
        if (!getHealth().isFull()) {
          int healing = getHealth().getMaximum() * cycleDuration / SECONDS_TO_REGENERATE_FULL_HEALTH;
          getHealth().incrementBy(healing);
        }
      }
      Writer.writeString("You wake up.");
    } else {
      Writer.writeString("You can only sleep at night.");
    }
  }

  /**
   * Checks if the Hero can see a given Entity based on the luminosity of the Location the Hero is in and on the
   * visibility of the specified Entity.
   */
  private boolean canSee(Entity entity) {
    // The Hero is always able to find himself.
    return entity == this || entity.getVisibility().visibleUnder(getLocation().getLuminosity());
  }

  /**
   * Returns whether any Item of the current Location is visible to the Hero.
   */
  private boolean canSeeAnItem() {
    for (Item item : getLocation().getItemList()) {
      if (canSee(item)) {
        return true;
      }
    }
    return false;
  }

  private boolean canSeeAdjacentLocations() {
    return ADJACENT_LOCATIONS_VISIBILITY.visibleUnder(getLocation().getLuminosity());
  }

  private <T extends Entity> List<T> filterByVisibility(List<T> list) {
    List<T> visible = new ArrayList<T>();
    for (T entity : list) {
      if (canSee(entity)) {
        visible.add(entity);
      }
    }
    return visible;
  }

  private <T extends Entity> Matches<T> filterByVisibility(Matches<T> matches) {
    return Matches.fromCollection(filterByVisibility(matches.toList()));
  }

  /**
   * Prints the name of the player's current location and lists all creatures and items the character sees.
   *
   * @param walkedInFrom the Direction from which the Hero walked in. {@code null} if the Hero did not walk.
   */
  public void look(Direction walkedInFrom) {
    Location location = getLocation(); // Avoid multiple calls to the getter.
    Writer.writeString(walkedInFrom != null ? "You arrive at " : "You are at ", Constants.FORE_COLOR_NORMAL, false);
    Writer.writeString(location.getName().getSingular(), location.getDescription().getColor(), false);
    Writer.writeString(".", Constants.FORE_COLOR_NORMAL, false);
    String description = " " + location.getDescription().getInfo();
    description += " " + "It is " + location.getWorld().getPartOfDay().toString().toLowerCase() + ".";
    Writer.writeString(description);
    lookAdjacentLocations(walkedInFrom);
    lookCreatures();
    lookItems();
  }

  /**
   * Looks to the Locations adjacent to the one the Hero is in, informing if the Hero cannot see the adjacent
   * Locations.
   *
   * @param walkedInFrom the Direction from which the Hero walked in. {@code null} if the Hero did not walk.
   */
  private void lookAdjacentLocations(Direction walkedInFrom) {
    Writer.writeNewLine();
    if (!canSeeAdjacentLocations()) {
      Writer.writeString("You can't clearly see the surrounding locations.");
      return;
    }
    World world = Game.getGameState().getWorld();
    Point pos = Game.getGameState().getHeroPosition();
    HashMap<ColoredString, ArrayList<Direction>> visibleLocations = new HashMap<ColoredString, ArrayList<Direction>>();
    Collection<Direction> directions = Direction.getAllExcept(walkedInFrom); // Don't print the Location you just left.
    for (Direction dir : directions) {
      Point adjacentPoint = new Point(pos, dir);
      Location adjacentLocation = world.getLocation(adjacentPoint);
      ExplorationStatistics explorationStatistics = Game.getGameState().getStatistics().getExplorationStatistics();
      explorationStatistics.createEntryIfNotExists(adjacentPoint, adjacentLocation.getId());
      String name = adjacentLocation.getName().getSingular();
      Color color = adjacentLocation.getDescription().getColor();
      ColoredString locationName = new ColoredString(name, color);
      if (!visibleLocations.containsKey(locationName)) {
        visibleLocations.put(locationName, new ArrayList<Direction>());
      }
      visibleLocations.get(locationName).add(dir);
    }
    for (Entry<ColoredString, ArrayList<Direction>> entry : visibleLocations.entrySet()) {
      String text = String.format("To %s you see ", Utils.enumerate(entry.getValue()));
      Writer.writeString(text, Constants.FORE_COLOR_NORMAL, false);
      Writer.writeString(String.format("%s", entry.getKey().getString()), entry.getKey().getColor(), false);
      Writer.writeString(".");
    }
  }

  /**
   * Prints a human-readable description of what Creatures the Hero sees.
   */
  private void lookCreatures() {
    List<Creature> creatures = new ArrayList<Creature>(getLocation().getCreatures());
    creatures.remove(this);
    creatures = filterByVisibility(creatures);
    Writer.writeNewLine();
    writeCreatureSight(creatures);
  }

  /**
   * Prints a human-readable description of what the Hero sees on the ground.
   */
  private void lookItems() {
    List<Item> items = getLocation().getItemList();
    items = filterByVisibility(items);
    writeItemSight(items);
  }

  private Item selectInventoryItem(IssuedCommand issuedCommand) {
    if (getInventory().getItemCount() == 0) {
      Writer.writeString("Your inventory is empty.");
      return null;
    } else {
      return selectItem(issuedCommand, getInventory(), false);
    }
  }

  /**
   * Select an item of the current location based on the arguments of a command.
   *
   * @param issuedCommand an IssuedCommand object whose arguments will determine the item search
   * @return an Item or {@code null}
   */
  private Item selectLocationItem(IssuedCommand issuedCommand) {
    if (filterByVisibility(getLocation().getItemList()).isEmpty()) {
      Writer.writeString("You don't see any items here.");
      return null;
    } else {
      return selectItem(issuedCommand, getLocation().getInventory(), true);
    }
  }

  /**
   * Selects an item of the specified {@code BaseInventory} based on the arguments of a command.
   *
   * @param issuedCommand an IssuedCommand object whose arguments will determine the item search
   * @param inventory an object of a subclass of {@code BaseInventory}
   * @param checkForVisibility true if only visible items should be selectable
   * @return an Item or {@code null}
   */
  private Item selectItem(IssuedCommand issuedCommand, BaseInventory inventory, boolean checkForVisibility) {
    List<Item> visibleItems;
    if (checkForVisibility) {
      visibleItems = filterByVisibility(inventory.getItems());
    } else {
      visibleItems = inventory.getItems();
    }
    if (issuedCommand.hasArguments() || HeroUtils.checkIfAllEntitiesHaveTheSameName(visibleItems)) {
      return HeroUtils.findItem(visibleItems, issuedCommand.getArguments());
    } else {
      Writer.writeString("You must specify an item.");
      return null;
    }
  }

  /**
   * Issues this Hero to attack a target.
   *
   * @param issuedCommand the command issued by the player
   */
  public void attackTarget(IssuedCommand issuedCommand) {
    Creature target = selectTarget(issuedCommand);
    if (target != null) {
      Engine.battle(this, target);
    }
  }

  /**
   * Attempts to select a target from the current location using the player input.
   *
   * @param issuedCommand the command entered by the player
   * @return a target Creature or {@code null}
   */
  private Creature selectTarget(IssuedCommand issuedCommand) {
    List<Creature> visibleCreatures = filterByVisibility(getLocation().getCreatures());
    if (issuedCommand.hasArguments() || HeroUtils.checkIfAllEntitiesHaveTheSameName(visibleCreatures, this)) {
      return findCreature(issuedCommand.getArguments());
    } else {
      Writer.writeString("You must specify a target.");
      return null;
    }
  }

  /**
   * Attempts to find a creature in the current location comparing its name to an array of string tokens.
   *
   * <p>If there are no matches, {@code null} is returned.
   *
   * <p>If there is one match, it is returned.
   *
   * <p>If there are multiple matches but all have the same name, the first one is returned.
   *
   * <p>If there are multiple matches with only two different names and one of these names is the Hero's name, the first
   * creature match is returned.
   *
   * <p>Lastly, if there are multiple matches that do not fall in one of the two categories above, {@code null} is
   * returned.
   *
   * @param tokens an array of string tokens.
   * @return a Creature or null.
   */
  public Creature findCreature(String[] tokens) {
    Matches<Creature> result = Utils.findBestCompleteMatches(getLocation().getCreatures(), tokens);
    result = filterByVisibility(result);
    if (result.size() == 0) {
      Writer.writeString("Creature not found.");
    } else if (result.size() == 1 || result.getDifferentNames() == 1) {
      return result.getMatch(0);
    } else if (result.getDifferentNames() == 2 && result.hasMatchWithName(getName())) {
      return result.getMatch(0).getName().equals(getName()) ? result.getMatch(1) : result.getMatch(0);
    } else {
      Messenger.printAmbiguousSelectionMessage();
    }
    return null;
  }

  /**
   * Attempts to pick an Item and add it to the inventory.
   */
  public void pickItem(IssuedCommand issuedCommand) {
    if (canSeeAnItem()) {
      Item selectedItem = selectLocationItem(issuedCommand);
      if (selectedItem != null) {
        SimulationResult result = getInventory().simulateItemAddition(selectedItem);
        if (result == SimulationResult.AMOUNT_LIMIT) {
          Writer.writeString("Your inventory is full.");
        } else if (result == SimulationResult.WEIGHT_LIMIT) {
          Writer.writeString("You can't carry more weight.");
        } else if (result == SimulationResult.SUCCESSFUL) {
          Engine.rollDateAndRefresh(SECONDS_TO_PICK_UP_AN_ITEM);
          if (getLocation().getInventory().hasItem(selectedItem)) {
            getLocation().removeItem(selectedItem);
            addItem(selectedItem);
          } else {
            HeroUtils.writeNoLongerInLocationMessage(selectedItem);
          }
        }
      }
    } else {
      Writer.writeString("You do not see any item you could pick up.");
    }
  }

  /**
   * Adds an Item object to the inventory. As a precondition, simulateItemAddition(Item) should return SUCCESSFUL.
   *
   * <p>Writes a message about this to the screen.
   *
   * @param item the Item to be added, not null
   */
  public void addItem(Item item) {
    if (getInventory().simulateItemAddition(item) == SimulationResult.SUCCESSFUL) {
      getInventory().addItem(item);
      Writer.writeString(String.format("Added %s to the inventory.", item.getQualifiedName()));
    } else {
      throw new IllegalStateException("simulateItemAddition did not return SUCCESSFUL.");
    }
  }

  /**
   * Tries to equip an item from the inventory.
   */
  public void parseEquip(IssuedCommand issuedCommand) {
    Item selectedItem = selectInventoryItem(issuedCommand);
    if (selectedItem != null) {
      if (selectedItem.hasTag(Item.Tag.WEAPON)) {
        equipWeapon(selectedItem);
      } else {
        Writer.writeString("You cannot equip that.");
      }
    }
  }

  /**
   * Attempts to drop an item from the hero's inventory.
   */
  public void dropItem(IssuedCommand issuedCommand) {
    Item selectedItem = selectInventoryItem(issuedCommand);
    if (selectedItem != null) {
      if (selectedItem == getWeapon()) {
        unsetWeapon(); // Just unset the weapon, it does not need to be moved to the inventory before being dropped.
      }
      // Take the time to drop the item.
      Engine.rollDateAndRefresh(SECONDS_TO_DROP_AN_ITEM);
      if (getInventory().hasItem(selectedItem)) { // The item may have disappeared while dropping.
        dropItem(selectedItem); // Just drop it if has not disappeared.
      }
      // The character "dropped" the item even if it disappeared while doing it, so write about it.
      Writer.writeString(String.format("Dropped %s.", selectedItem.getQualifiedName()));
    }
  }

  public void printInventory() {
    Name item = NameFactory.newInstance("item");
    String firstLine;
    if (getInventory().getItemCount() == 0) {
      firstLine = "Your inventory is empty.";
    } else {
      String itemCount = item.getQuantifiedName(getInventory().getItemCount(), QuantificationMode.NUMBER);
      firstLine = "You are carrying " + itemCount + ". Your inventory weights " + getInventory().getWeight() + ".";
    }
    Writer.writeString(firstLine);
    // Local variable to improve readability.
    String itemLimit = item.getQuantifiedName(getInventory().getItemLimit(), QuantificationMode.NUMBER);
    Writer.writeString(
        "Your maximum carrying capacity is " + itemLimit + " and " + getInventory().getWeightLimit() + ".");
    if (getInventory().getItemCount() != 0) {
      printItems();
    }
  }

  /**
   * Prints all items in the Hero's inventory. This function should only be called if the inventory is not empty.
   */
  private void printItems() {
    if (getInventory().getItemCount() == 0) {
      throw new IllegalStateException("inventory item count is 0.");
    }
    Writer.writeString("You are carrying:");
    for (Item item : getInventory().getItems()) {
      String name = String.format("%s (%s)", item.getQualifiedName(), item.getWeight());
      if (hasWeapon() && getWeapon() == item) {
        Writer.writeString(" [Equipped] " + name);
      } else {
        Writer.writeString(" " + name);
      }
    }
  }

  /**
   * Attempts to eat an item from the ground.
   */
  public void eatItem(IssuedCommand issuedCommand) {
    Item selectedItem = selectInventoryItem(issuedCommand);
    if (selectedItem != null) {
      if (selectedItem.hasTag(Item.Tag.FOOD)) {
        Engine.rollDateAndRefresh(SECONDS_TO_EAT_AN_ITEM);
        if (getInventory().hasItem(selectedItem)) {
          FoodComponent food = selectedItem.getFoodComponent();
          double remainingBites = selectedItem.getIntegrity().getCurrent() / (double) food.getIntegrityDecrementOnEat();
          int healthIncrement;
          if (remainingBites >= 1.0) {
            healthIncrement = food.getNutrition();
          } else {
            // The healing may vary from 0 up to (nutrition - 1) if there is not enough for a bite.
            healthIncrement = (int) (food.getNutrition() * remainingBites);
          }
          selectedItem.decrementIntegrityByEat();
          if (selectedItem.isBroken() && !selectedItem.hasTag(Item.Tag.REPAIRABLE)) {
            Writer.writeString("You ate " + selectedItem.getName() + ".");
          } else {
            Writer.writeString("You ate a bit of " + selectedItem.getName() + ".");
          }
          addHealth(healthIncrement);
        } else {
          HeroUtils.writeNoLongerInInventoryMessage(selectedItem);
        }
      } else {
        Writer.writeString("You can only eat food.");
      }
    }
  }

  /**
   * The method that enables a Hero to drink milk from a Creature.
   *
   * @param issuedCommand the command entered by the player
   */
  public void parseMilk(IssuedCommand issuedCommand) {
    if (issuedCommand.hasArguments()) { // Specified which creature to milk from.
      Creature selectedCreature = selectTarget(issuedCommand); // Finds the best match for the specified arguments.
      if (selectedCreature != null) {
        if (selectedCreature.hasTag(Creature.Tag.MILKABLE)) {
          milk(selectedCreature);
        } else {
          Writer.writeString("This creature is not milkable.");
        }
      }
    } else { // Filter milkable creatures.
      List<Creature> visibleCreatures = filterByVisibility(getLocation().getCreatures());
      List<Creature> milkableCreatures = HeroUtils.filterByTag(visibleCreatures, Tag.MILKABLE);
      if (milkableCreatures.isEmpty()) {
        Writer.writeString("You can't find a milkable creature.");
      } else {
        if (Matches.fromCollection(milkableCreatures).getDifferentNames() == 1) {
          milk(milkableCreatures.get(0));
        } else {
          Writer.writeString("You need to be more specific.");
        }
      }
    }
  }

  private void milk(Creature creature) {
    Engine.rollDateAndRefresh(SECONDS_TO_MILK_A_CREATURE);
    Writer.writeString("You drink milk directly from " + creature.getName().getSingular() + ".");
    addHealth(MILK_NUTRITION);
  }

  public void readItem(IssuedCommand issuedCommand) {
    Item selectedItem = selectInventoryItem(issuedCommand);
    if (selectedItem != null) {
      BookComponent book = selectedItem.getBookComponent();
      if (book != null) {
        Engine.rollDateAndRefresh(book.getTimeToRead());
        if (getInventory().hasItem(selectedItem)) { // Just in case if a readable item eventually decomposes.
          Writer.writeString(book.getText());
          Writer.writeNewLine();
          if (book.isDidactic()) {
            learnSpell(book);
          }
        } else {
          HeroUtils.writeNoLongerInInventoryMessage(selectedItem);
        }
      } else {
        Writer.writeString("You can only read books.");
      }
    }
  }

  /**
   * Attempts to learn a spell from a BookComponent object. As a precondition, book must be didactic (teach a spell).
   *
   * @param book a BookComponent that returns true to isDidactic, not null
   */
  private void learnSpell(@NotNull BookComponent book) {
    if (!book.isDidactic()) {
      throw new IllegalArgumentException("book should be didactic.");
    }
    Spell spell = SpellData.getSpellMap().get(book.getSpellId());
    if (getSpellcaster().knowsSpell(spell)) {
      Writer.writeString("You already knew " + spell.getName().getSingular() + ".");
    } else {
      getSpellcaster().learnSpell(spell);
      Writer.writeString("You learned " + spell.getName().getSingular() + ".");
    }
  }

  /**
   * Tries to destroy an item from the current location.
   */
  public void destroyItem(IssuedCommand issuedCommand) {
    Item target = selectLocationItem(issuedCommand);
    if (target != null) {
      if (target.isBroken()) {
        Writer.writeString(target.getName() + " is already crashed.");
      } else {
        Engine.rollDateAndRefresh(SECONDS_TO_DESTROY_AN_ITEM); // Time passes before destroying the item.
        if (getLocation().getInventory().hasItem(target)) {
          target.decrementIntegrityToZero();
          String verb = target.hasTag(Item.Tag.REPAIRABLE) ? "crashed" : "destroyed";
          Writer.writeString(getName() + " " + verb + " " + target.getName() + ".");
        } else {
          HeroUtils.writeNoLongerInLocationMessage(target);
        }
      }
    }
  }

  private void equipWeapon(Item weapon) {
    if (hasWeapon()) {
      if (getWeapon() == weapon) {
        Writer.writeString(getName() + " is already equipping " + weapon.getName() + ".");
        return;
      } else {
        unequipWeapon();
      }
    }
    Engine.rollDateAndRefresh(SECONDS_TO_EQUIP);
    if (getInventory().hasItem(weapon)) {
      setWeapon(weapon);
      Writer.writeString(getName() + " equipped " + weapon.getQualifiedName() + ".");
    } else {
      HeroUtils.writeNoLongerInInventoryMessage(weapon);
    }
  }

  public void unequipWeapon() {
    if (hasWeapon()) {
      Engine.rollDateAndRefresh(SECONDS_TO_UNEQUIP);
    }
    if (hasWeapon()) { // The weapon may have disappeared.
      Item equippedWeapon = getWeapon();
      unsetWeapon();
      Writer.writeString(getName() + " unequipped " + equippedWeapon.getName() + ".");
    } else {
      Writer.writeString("You are not equipping a weapon.");
    }
  }

  /**
   * Prints a message with the current status of the Hero.
   */
  public void printAllStatus() {
    StringBuilder builder = new StringBuilder();
    builder.append(getName()).append("\n");
    builder.append("You are ");
    builder.append(getHealth().getHealthState().toString().toLowerCase()).append(".\n");
    builder.append("Your base attack is ").append(String.valueOf(getAttack())).append(".\n");
    if (hasWeapon()) {
      String format = "You are currently equipping %s, whose base damage is %d. This makes your total damage %d.\n";
      int weaponBaseDamage = getWeapon().getWeaponComponent().getDamage();
      int totalDamage = getAttack() + weaponBaseDamage;
      builder.append(String.format(format, getWeapon().getQualifiedName(), weaponBaseDamage, totalDamage));
    } else {
      builder.append("You are fighting bare-handed.\n");
    }
    Writer.writeString(builder.toString());
  }

  /**
   * Prints the Hero's age.
   */
  public void printAge() {
    String age = new Period(dateOfBirth, Game.getGameState().getWorld().getWorldDate()).toString();
    Writer.writeString(String.format("You are %s old.", age), Color.CYAN);
  }

  /**
   * Makes the Hero read the current date and time as well as he can.
   */
  public void readTime() {
    Item clock = getBestClock();
    if (clock != null) {
      Writer.writeString(clock.getClockComponent().getTimeString());
      // Assume that the hero takes the same time to read the clock and to put it back where it was.
      Engine.rollDateAndRefresh(getTimeToReadFromClock(clock));
    }
    World world = getLocation().getWorld();
    Date worldDate = getLocation().getWorld().getWorldDate();
    Writer.writeString("You think it is " + worldDate.toDateString() + ".");
    if (worldDate.getMonth() == dateOfBirth.getMonth() && worldDate.getDay() == dateOfBirth.getDay()) {
      Writer.writeString("Today is your birthday.");
    }
    Writer.writeString("You can see that it is " + world.getPartOfDay().toString().toLowerCase() + ".");
  }

  /**
   * Gets the easiest-to-access unbroken clock of the Hero. If the Hero has no unbroken clock, the easiest-to-access
   * broken clock. Lastly, if the Hero does not have a clock at all, null is returned.
   *
   * @return an Item object of the clock Item (or null)
   */
  @Nullable
  private Item getBestClock() {
    Item clock = null;
    if (hasWeapon() && getWeapon().hasTag(Item.Tag.CLOCK)) {
      if (!getWeapon().isBroken()) {
        clock = getWeapon();
      } else { // The Hero is equipping a broken clock: check if he has a working one in his inventory.
        for (Item item : getInventory().getItems()) {
          if (item.hasTag(Item.Tag.CLOCK) && !item.isBroken()) {
            clock = item;
            break;
          }
        }
        if (clock == null) {
          clock = getWeapon(); // The Hero does not have a working clock in his inventory: use the equipped one.
        }
      }
    } else { // The Hero is not equipping a clock.
      Item brokenClock = null;
      for (Item item : getInventory().getItems()) {
        if (item.hasTag(Item.Tag.CLOCK)) {
          if (item.isBroken() && brokenClock == null) {
            brokenClock = item;
          } else {
            clock = item;
            break;
          }
        }
      }
      if (brokenClock != null) {
        clock = brokenClock;
      }
    }
    if (clock != null) {
      Engine.rollDateAndRefresh(getTimeToReadFromClock(clock));
    }
    return clock;
  }

  private int getTimeToReadFromClock(@NotNull Item clock) {
    return clock == getWeapon() ? SECONDS_TO_READ_EQUIPPED_CLOCK : SECONDS_TO_READ_UNEQUIPPED_CLOCK;
  }

  /**
   * Writes a list with all the Spells that the Hero knows.
   */
  public void writeSpellList() {
    DungeonStringBuilder builder = new DungeonStringBuilder();
    if (getSpellcaster().getSpellList().isEmpty()) {
      builder.append("You have not learned any spells yet.");
    } else {
      builder.append("You know ");
      builder.append(Utils.enumerate(getSpellcaster().getSpellList()));
      builder.append(".");
    }
    Writer.write(builder);
  }

}
