# Custom Inventory

A redesigned, extensible player inventory for Hytale, with an extra **Gear** panel for accessory equipment and an API that lets other mods add pages, buttons, equipment and visuals without colliding with each other.

## Features

### Redesigned Inventory

* Replaces the Adventure-mode inventory (opened with the usual inventory key) with a single, unified screen
* Player panel with character preview, health, stamina, mana and defense
* Armor slots with an **eye toggle** on each piece to show or hide it on your character, while keeping its stats
* Utility slot with a quick-select wheel (**Z**)
* Built-in pages:
  * **Pocket Crafting**
  * **Collected Memories**
  * **Backpack**
* Inventory actions: sort items, drop stacks, drop every stack of a type, take all, deposit all and quick stack
* Full mouse and gamepad support, with automatic, keyboard or controller button prompts

You can also open the inventory at any time with:

```
/custominventory
```

### Gear Panel

A collapsible panel next to your character with four extra equipment slots:

* **Hat**
* **Backpack**
* **Collar**
* **Belt**

Gear items are saved with your character and follow the world's normal item-loss rules on death.

### Equipment Visibility

Every Gear slot has its own **eye toggle**:

* Click the eye beside an equipped item to show or hide it on your character
* Each slot is independent: hiding your backpack does not hide your hat
* Hiding an item only changes how you look. The item stays equipped and keeps working
* Your choice is saved and kept after you reconnect
* Some functional items (such as the Helipack) are always visible, and their eye is shown as locked

### Shared Character Rendering

Custom Inventory draws everything that mods attach to your character: equipped Gear items, a quiver and so on. Several equipment mods can be installed together without one hiding the other's models. Your skin, armor visibility settings and hairstyle under headgear are kept intact.

## For Mod Developers

Custom Inventory is designed to be built upon. Through `CustomInventoryPlugin.get()` and the `api` package you can:

* Register inventory **pages**, **buttons**, **UI extensions** and **item tooltips** (`InventoryRegistry`)
* Open your own screens inside the shared inventory, with the player and inventory panels already handled (`openView`)
* Register items for the Gear slots and react to equipping or unequipping (`ExtraEquipment`, `EquipmentManager`)
* Show your equipment on the player model and respect each slot's eye toggle automatically (`EquipmentManager.Listener#attachment`)
* Add other attachments, animations and rebuild callbacks to the player model safely (`PlayerModel`)

See [docs/equipment-api.md](docs/equipment-api.md) for the equipment and rendering API.

> **Important:** never replace the player's `ModelComponent` yourself. Use `EquipmentManager` or `PlayerModel` so every mod's visuals are kept.

## Compatibility

* Works in **Adventure** mode; other game modes keep the original inventory
* Compatible with all armor sets
* Required by [True Backpack](https://github.com/SupremoSama/Hytale-TrueBackpack), which adds backpacks, the HeadLight hat, quivers and the Helipack to the Gear panel

## Installation

1. Place the Custom Inventory JAR in your server's `mods` folder
2. Install any mods that depend on it, such as True Backpack
3. Start the server

## Source Code

Interested in contributing or building on top of the project?

GitHub: [SupremoSama/Hytale-CustomInventory](https://github.com/SupremoSama/Hytale-CustomInventory)

Custom Inventory Team
