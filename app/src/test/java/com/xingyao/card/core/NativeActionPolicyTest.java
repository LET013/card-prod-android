package com.xingyao.card.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NativeActionPolicyTest {
    @Test
    public void publicRecognitionAndStatusActionsDoNotRequireAdminSession() {
        assertTrue(NativeActionPolicy.isPublicAction("face.verify"));
        assertTrue(NativeActionPolicy.isPublicAction("cabinet.getSlots"));
        assertTrue(NativeActionPolicy.isPublicAction("settings.load"));
        assertTrue(NativeActionPolicy.isPublicAction("status.reportNow"));
        assertNull(NativeActionPolicy.requiredPermission("face.verify"));
        assertNull(NativeActionPolicy.requiredPermission("status.reportNow"));
    }

    @Test
    public void dangerousActionsRequireNativePermissions() {
        assertEquals("debug.command", NativeActionPolicy.requiredPermission("serial.send"));
        assertEquals("cabinet.unlockAll", NativeActionPolicy.requiredPermission("cabinet.unlockAll"));
        assertEquals("auth.password.manage", NativeActionPolicy.requiredPermission("auth.changePassword"));
    }

    @Test
    public void unknownActionsAreNotSilentlyAccepted() {
        assertFalse(NativeActionPolicy.isKnownAction("cabinet.magicOpen"));
    }
}
