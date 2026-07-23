package com.xingyao.card.core;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SlotStateRepositoryTest {
    @Test
    public void noKnownCardDoesNotFallBackToSlotOne() throws Exception {
        SlotStateRepository repository = new SlotStateRepository();
        repository.configure(new JSONObject().put("totalCount", 10).put("singleGroupCount", 10));
        assertEquals(-1, repository.pickTakeSlot());
        assertEquals(0, repository.summary().optInt("knownSlots"));
    }

    @Test
    public void knownOccupiedSlotCanBeSelected() throws Exception {
        SlotStateRepository repository = new SlotStateRepository();
        repository.configure(new JSONObject().put("totalCount", 10).put("singleGroupCount", 10));
        repository.updateSlot(new JSONObject().put("slotNumber", 4).put("status", "FULL")
                .put("updatedAt", System.currentTimeMillis()));
        assertEquals(4, repository.pickTakeSlot());
        assertEquals(1, repository.summary().optInt("knownSlots"));
    }
}
