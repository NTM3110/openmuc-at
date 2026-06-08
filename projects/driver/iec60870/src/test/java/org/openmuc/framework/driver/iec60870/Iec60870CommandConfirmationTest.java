/*
 * Copyright 2011-2024 Fraunhofer ISE
 *
 * This file is part of OpenMUC.
 */
package org.openmuc.framework.driver.iec60870;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openmuc.framework.config.ArgumentSyntaxException;
import org.openmuc.framework.data.Flag;
import org.openmuc.framework.data.Record;
import org.openmuc.framework.driver.iec60870.settings.ChannelAddress;
import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.InformationObject;

class Iec60870CommandConfirmationTest {

    @Test
    void activationConfirmationCreatesStringRecord() throws ArgumentSyntaxException {
        InformationObject object = new InformationObject(2045, new IeSingleCommand(true, 0, false));
        ASdu asdu = new ASdu(ASduType.C_SC_NA_1, false, CauseOfTransmission.ACTIVATION_CON, false, false, 0, 1, object);
        ChannelAddress address = new ChannelAddress("ca=1;t=45;ioa=2045;dt=actcon");

        Record record = Iec60870DataHandling.handleInformationObject(asdu, 1234L, address, object);

        assertEquals(Flag.VALID, record.getFlag());
        String value = record.getValue().asString();
        assertTrue(value.contains("\"cot\":\"ACTIVATION_CON\""));
        assertTrue(value.contains("\"negative\":false"));
        assertTrue(value.contains("\"typeId\":45"));
        assertTrue(value.contains("\"type\":\"C_SC_NA_1\""));
        assertTrue(value.contains("\"commonAddress\":1"));
        assertTrue(value.contains("\"ioa\":2045"));
    }

    @Test
    void activationRequestDoesNotCreateConfirmationRecord() throws ArgumentSyntaxException {
        InformationObject object = new InformationObject(2045, new IeSingleCommand(true, 0, false));
        ASdu asdu = new ASdu(ASduType.C_SC_NA_1, false, CauseOfTransmission.ACTIVATION, false, false, 0, 1, object);
        ChannelAddress address = new ChannelAddress("ca=1;t=45;ioa=2045;dt=actcon");

        Record record = Iec60870DataHandling.handleInformationObject(asdu, 1234L, address, object);

        assertEquals(Flag.NO_VALUE_RECEIVED_YET, record.getFlag());
    }
}
