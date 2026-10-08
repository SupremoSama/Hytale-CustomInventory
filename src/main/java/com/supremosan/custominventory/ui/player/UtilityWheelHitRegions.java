package com.supremosan.custominventory.ui.player;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.InventoryEventBindings;

/** Scanline hit areas for the native 310px, five-slice inventory wheel.
 * Legacy CustomUI has rectangular hit testing; a render mask does not shape it.
 */
public final class UtilityWheelHitRegions {
    private static final int SIZE = 310;
    private static final int STRIP = 4;

    private UtilityWheelHitRegions() { }

    public static int slotAt(double x, double y) {
        double dx = x - SIZE / 2.0;
        double dy = y - SIZE / 2.0;
        double radius = Math.hypot(dx, dy);
        if (radius < 70 || radius >= 155) return -2;
        // Clockwise from twelve o'clock: upper-right, lower-right, clear,
        // lower-left, upper-left. The native slice boundaries are 72 degrees.
        int slice = (int) ((Math.toDegrees(Math.atan2(dx, -dy)) + 360) % 360 / 72);
        return switch (slice) {
            case 0 -> 2;
            case 1 -> 3;
            case 2 -> -1;
            case 3 -> 0;
            default -> 1;
        };
    }

    static void build(UICommandBuilder commands, InventoryEventBindings events, String host) {
        var markup = new StringBuilder();
        int id = 0;
        for (int top = 0; top < SIZE; top += STRIP) {
            int height = Math.min(STRIP, SIZE - top);
            int left = 0;
            while (left < SIZE) {
                int slot = slotAt(left + .5, top + height / 2.0);
                int right = left + 1;
                while (right < SIZE && slotAt(right + .5, top + height / 2.0) == slot) right++;
                if (slot != -2) {
                    String selector = "#UtilityWedgeHit" + id++;
                    markup.append("Button ").append(selector).append(" { Anchor: (Left: ")
                            .append(left).append(", Top: ").append(top).append(", Width: ")
                            .append(right - left).append(", Height: ").append(height)
                            .append("); Background: #000000(0); Style: (Default: (), Hovered: (), Pressed: ()); }\n");
                    events.bind(CustomUIEventBindingType.MouseEntered, selector, UtilitySlotSelector.HOVER, Integer.toString(slot), false);
                    events.bind(CustomUIEventBindingType.MouseExited, selector, UtilitySlotSelector.UNHOVER, Integer.toString(slot), false);
                    events.bind(CustomUIEventBindingType.Activating, selector, UtilitySlotSelector.SELECT, Integer.toString(slot), false);
                }
                left = right;
            }
        }
        commands.appendInline(host + " #UtilityWedgeHitRegions", markup.toString());
    }
}
