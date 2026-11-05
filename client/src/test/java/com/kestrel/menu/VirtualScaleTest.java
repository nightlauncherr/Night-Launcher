package com.kestrel.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VirtualScaleTest {
    @Test
    public void zoomsOnlyWhenTheGuiScaleLeavesRoom() {
        // 1269x951 window: Auto/Large GUI scale (317 tall) keeps 1x, Small (951 tall) zooms 3x.
        assertEquals(1, VirtualScale.factor(317));
        assertEquals(3, VirtualScale.factor(951));
        // Normal on that window (475 tall): still 1x. 1080p at Small: 4x. Tiny windows: never below 1x.
        assertEquals(1, VirtualScale.factor(475));
        assertEquals(4, VirtualScale.factor(1080));
        assertEquals(1, VirtualScale.factor(120));
    }
}
