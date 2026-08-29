package com.xingyao.card.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.xingyao.card.core.local.auth.NativeActionPolicy;

public class NativeActionPolicyTest {
    @Test
    public void publicRecognitionActionsDoNotRequireAdminSession() {
        assertTrue(NativeActionPolicy.isPublicAction("face.verify"));
        assertTrue(NativeActionPolicy.isPublicAction("cabinet.getSlots"));
        assertTrue(NativeActionPolicy.isPublicAction("settings.load"));
        assertTrue(NativeActionPolicy.isPublicAction("device.activate"));
        assertTrue(NativeActionPolicy.isPublicAction("app.updateStatus"));
        assertTrue(NativeActionPolicy.isPublicAction("app.downloadUpdate"));
        assertNull(NativeActionPolicy.requiredPermission("face.verify"));
    }

    @Test
    public void dangerousActionsRequireNativePermissions() {
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.send"));
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.setDebugLogging"));
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.setLedDutyCycle"));
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.disconnect"));
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.openDoor"));
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.querySlot"));
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.readVersion"));
        assertEquals("upgrade.firmware", NativeActionPolicy.requiredPermission("serial.firmwareUpgrade"));
        assertEquals("cabinet.unlockAll", NativeActionPolicy.requiredPermission("cabinet.unlockAll"));
        assertEquals("employee.view", NativeActionPolicy.requiredPermission("employee.sync"));
        assertEquals("auth.password.manage", NativeActionPolicy.requiredPermission("auth.changePassword"));
    }

    @Test
    public void unknownActionsAreNotSilentlyAccepted() {
        assertFalse(NativeActionPolicy.isKnownAction("cabinet.magicOpen"));
    }
}
