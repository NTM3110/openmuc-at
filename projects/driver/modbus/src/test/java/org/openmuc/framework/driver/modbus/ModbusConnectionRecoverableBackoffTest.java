/*
 * Copyright 2011-2024 Fraunhofer ISE
 *
 * This file is part of OpenMUC.
 * For more information visit http://www.openmuc.org
 *
 * OpenMUC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenMUC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenMUC. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.openmuc.framework.driver.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openmuc.framework.config.ArgumentSyntaxException;
import org.openmuc.framework.config.ChannelScanInfo;
import org.openmuc.framework.config.ScanException;
import org.openmuc.framework.data.Flag;
import org.openmuc.framework.data.Record;
import org.openmuc.framework.data.Value;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.driver.spi.ChannelRecordContainer;
import org.openmuc.framework.driver.spi.ChannelValueContainer;
import org.openmuc.framework.driver.spi.ConnectionException;
import org.openmuc.framework.driver.spi.RecordsReceivedListener;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.ModbusIOException;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

class ModbusConnectionRecoverableBackoffTest {

    @Test
    void skipsOnlyFailedUnitDuringRecoverableBackoff() throws Exception {
        TestConnection connection = new TestConnection();
        connection.setRecoverableReadBackoffMs(1000);
        List<ChannelRecordContainer> unitSeven = List.of(new TestContainer("7:HOLDING_REGISTERS:1:INT16"));
        List<ChannelRecordContainer> unitEight = List.of(new TestContainer("8:HOLDING_REGISTERS:1:INT16"));

        connection.failNextRead = true;
        Object handle = connection.readChannelGroupHighLevel(unitSeven, null, "unit7");

        assertNotNull(handle);
        assertEquals(1, connection.readCount);
        assertEquals(1, connection.disconnectCount);
        assertEquals(1, connection.connectCount);
        assertEquals(Flag.DRIVER_ERROR_CHANNEL_TEMPORARILY_NOT_ACCESSIBLE, unitSeven.get(0).getRecord().getFlag());

        connection.readChannelGroupHighLevel(unitSeven, handle, "unit7");

        assertEquals(1, connection.readCount);
        assertEquals(1, connection.disconnectCount);
        assertEquals(1, connection.connectCount);
        assertEquals(Flag.DRIVER_ERROR_CHANNEL_TEMPORARILY_NOT_ACCESSIBLE, unitSeven.get(0).getRecord().getFlag());

        connection.readChannelGroupHighLevel(unitEight, null, "unit8");

        assertEquals(2, connection.readCount);
        assertEquals(Flag.VALID, unitEight.get(0).getRecord().getFlag());
    }

    @Test
    void successfulReadAfterBackoffClearsFailedUnit() throws Exception {
        TestConnection connection = new TestConnection();
        connection.setRecoverableReadBackoffMs(1000);
        List<ChannelRecordContainer> containers = List.of(new TestContainer("7:HOLDING_REGISTERS:1:INT16"));

        connection.failNextRead = true;
        Object handle = connection.readChannelGroupHighLevel(containers, null, "unit7");

        connection.timeMillis = 1001;
        Object reusedHandle = connection.readChannelGroupHighLevel(containers, handle, "unit7");

        assertSame(handle, reusedHandle);
        assertEquals(2, connection.readCount);
        assertEquals(Flag.VALID, containers.get(0).getRecord().getFlag());

        connection.failNextRead = true;
        connection.readChannelGroupHighLevel(containers, handle, "unit7");

        assertEquals(3, connection.readCount);
        assertEquals(2, connection.disconnectCount);
        assertEquals(2, connection.connectCount);
        assertEquals(Flag.DRIVER_ERROR_CHANNEL_TEMPORARILY_NOT_ACCESSIBLE, containers.get(0).getRecord().getFlag());
    }

    private static final class TestConnection extends ModbusConnection {
        private int readCount;
        private int connectCount;
        private int disconnectCount;
        private long timeMillis;
        private boolean failNextRead;

        @Override
        public void connect() {
            connectCount++;
        }

        @Override
        public void disconnect() {
            disconnectCount++;
        }

        @Override
        protected boolean isReadIOExceptionRecoverable() {
            return true;
        }

        @Override
        protected long currentTimeMillis() {
            return timeMillis;
        }

        @Override
        public Register[] readHoldingRegisters(ModbusChannelGroup channelGroup) throws ModbusException {
            readCount++;
            assertTrue(channelGroup.getUnitId() == 7 || channelGroup.getUnitId() == 8);
            if (failNextRead) {
                failNextRead = false;
                throw new ModbusIOException("simulated timeout");
            }
            return new Register[] { new SimpleRegister(42) };
        }

        @Override
        public List<ChannelScanInfo> scanForChannels(String settings)
                throws UnsupportedOperationException, ArgumentSyntaxException, ScanException, ConnectionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object read(List<ChannelRecordContainer> containers, Object containerListHandle, String samplingGroup)
                throws UnsupportedOperationException, ConnectionException {
            return readChannelGroupHighLevel(containers, containerListHandle, samplingGroup);
        }

        @Override
        public void startListening(List<ChannelRecordContainer> containers, RecordsReceivedListener listener)
                throws UnsupportedOperationException, ConnectionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object write(List<ChannelValueContainer> containers, Object containerListHandle)
                throws UnsupportedOperationException, ConnectionException {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestContainer implements ChannelRecordContainer {
        private final String channelAddress;
        private Record record;
        private Object channelHandle;

        private TestContainer(String channelAddress) {
            this.channelAddress = channelAddress;
        }

        @Override
        public String getChannelAddress() {
            return channelAddress;
        }

        @Override
        public Object getChannelHandle() {
            return channelHandle;
        }

        @Override
        public void setChannelHandle(Object handle) {
            channelHandle = handle;
        }

        @Override
        public void setRecord(Record record) {
            this.record = record;
        }

        @Override
        public ChannelRecordContainer copy() {
            TestContainer copy = new TestContainer(channelAddress);
            copy.record = record;
            copy.channelHandle = channelHandle;
            return copy;
        }

        @Override
        public Record getRecord() {
            return record;
        }

        @Override
        public Channel getChannel() {
            return null;
        }
    }
}
