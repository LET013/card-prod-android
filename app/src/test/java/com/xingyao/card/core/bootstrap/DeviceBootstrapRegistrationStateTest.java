package com.xingyao.card.core.bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeviceBootstrapRegistrationStateTest {

    @Test
    public void registrationRequiresBothTokenAndDeviceCode() {
        assertFalse(DeviceBootstrapManager.hasCompleteRegistration("", ""));
        assertFalse(DeviceBootstrapManager.hasCompleteRegistration("token", ""));
        assertFalse(DeviceBootstrapManager.hasCompleteRegistration("", "201A9907"));
        assertFalse(DeviceBootstrapManager.hasCompleteRegistration("  ", "201A9907"));
        assertFalse(DeviceBootstrapManager.hasCompleteRegistration("token", "  "));
        assertTrue(DeviceBootstrapManager.hasCompleteRegistration("token", "201A9907"));
    }
}
