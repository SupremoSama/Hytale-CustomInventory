# Equipment API

CustomInventory owns the four Gear slots (`ExtraEquipment.HAT`, `BACKPACK`, `COLLAR`, `BELT`):
their saved inventory, item eligibility, equip/unequip notifications, an independent saved
visibility state per slot, and the rendering of equipped items on the player model. Mods that
add equipment register their items and a listener that describes the item's model.
Every call and callback runs on the player's world thread.

## Registering equipment

```java
ExtraEquipment.registerItems(ExtraEquipment.HAT, item -> MyHats.isHat(item.getItemId()));
var registration = EquipmentManager.addListener(ExtraEquipment.HAT, new EquipmentManager.Listener() {
    @Override public void onEquipmentChanged(EquipmentManager.Change change) {
        // change.previous() / change.current(); use change.commands() for structural ECS changes.
    }
    @Override public ModelAttachment attachment(Ref<EntityStore> ref, Store<EntityStore> store, ItemStack equipped) {
        return new ModelAttachment(MyHats.model(equipped), MyHats.texture(equipped), null, null, 1.0);
    }
    @Override public boolean canHide(ItemStack equipped) {
        return true; // false keeps functional items rendered; the eye is then shown as locked.
    }
});
// On shutdown:
registration.close();
```

`onEquipmentChanged` fires when a slot's item differs from the last announced one: equipped,
removed, replaced, or its metadata/durability changed. When a player becomes ready, occupied
slots are announced again (with an empty `previous`) so listeners can rebuild visuals after a
load.

## Operations

| Method | Purpose |
| --- | --- |
| `getEquipped(ref, store, slot)` | Equipped item, or `null`. |
| `equip(ref, store, slot, source, sourceSlot)` | Moves an eligible item in; an occupied slot swaps back into the source. |
| `unequip(ref, store, slot, target)` | Moves the equipped item into `target`. |
| `isVisible(ref, store, slot)` | Saved eye state for the slot (default visible). |
| `setVisible` / `toggleVisible` | Changes one slot and notifies only that slot's listeners. |
| `canHide(ref, store, slot)` | False when a listener locks the equipped item visible. |

## Rendering

CustomInventory is the only writer of the player's `ModelComponent`. Mods must not replace it
themselves: two mods doing so overwrite each other's attachments.

- Equipped items: return the model from `Listener.attachment`. It is rendered while the slot's eye
  is open (or while `canHide` is false) and re-rendered on every equip, change and eye toggle.
- Anything else (e.g. a quiver shown while arrows are carried): `PlayerModel.putAttachment(uuid,
  "mymod:quiver", attachment)` / `removeAttachment`. Keys are namespaced per mod.
- State an attachment depends on changed (e.g. an item was repainted): `PlayerModel.requestRebuild`.
- Extra animations for attached models: `PlayerModel.registerAnimationSet`.
- `PlayerModel.addRebuildListener` runs after a rebuild, e.g. to restart an interrupted animation.

The client draws a bare player model from the skin, so once anything is attached CustomInventory
rebuilds the skin parts as attachments too: armor that hides cosmetics and the player's armor
visibility settings are honored, and headgear that hides hair leaves a short inner hair. Players
nobody contributes to keep the native model. A player transformed into another model only gets
the contributions re-added.

## Gear eyes

The Gear panel shows an eye beside each occupied slot, aligned with the armor eyes. Clicking it
calls `toggleVisible` for that slot only. Visibility is saved on the `ExtraEquipment` component
(`HiddenSlots`), so it survives reconnects; saves without the field load with every slot visible.
Changing visibility never removes the item or its effects.
