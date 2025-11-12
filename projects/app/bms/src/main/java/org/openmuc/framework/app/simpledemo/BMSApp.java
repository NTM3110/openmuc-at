/*
 * This file is part of OpenMUC.
 * For more information visit http://www.openmuc.org
 *
 * You are free to use code of this sample file in any
 * way you like and without any restrictions.
 *
 */
package org.openmuc.framework.app.bms;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import org.openmuc.framework.data.DoubleValue;
import org.openmuc.framework.data.Flag;
import org.openmuc.framework.data.Record;
import org.openmuc.framework.data.StringValue;
import org.openmuc.framework.data.Value;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = {})
public final class BMSApp{

    private static final Logger logger = LoggerFactory.getLogger(BMSApp.class);
    private static final DecimalFormatSymbols DFS = DecimalFormatSymbols.getInstance(Locale.US);
    private static final DecimalFormat DF = new DecimalFormat("#0.000", DFS);

    // ChannelIDs, see conf/channel.xml
    private static final String CHANNEL = "str1_demo";


    // With the dataAccessService you can access to your measured and control data of your devices.
    @Reference
    private DataAccessService dataAccessService;

    private Timer updateTimer;

    /**
     * Every app needs one activate method. Is is called at begin. Here you can configure all you need at start of your
     * app. The Activate method can block the start of your OpenMUC, f.e. if you use Thread.sleep().
     */
    @Activate
    private void activate() {
        logger.info("Activating Demo App");
        init();
    }

    /**
     * Every app needs one deactivate method. It handles the shutdown of your app e.g. closing open streams.
     */
    @Deactivate
    private void deactivate() {
        logger.info("Deactivating Demo App");
        logger.info("DemoApp thread interrupted: will stop");
        updateTimer.cancel();
        updateTimer.purge();
    }

    /**
     * application logic
     */
    private void init() {
        logger.info("Demo App started running...");

        initUpdateTimer();
    }

    private void updateChannel(){
        try {
            Channel channel = dataAccessService.getChannel(CHANNEL);
            if (channel == null) {
                logger.error("Channel {} not found!", CHANNEL);
                return;
            }

            Record record = channel.getLatestRecord();

            double newValue = record.getValue().asDouble();

            logger.info("New value: {}", newValue);

        } catch (Exception e) {
            logger.error("Error updating channel {}: {}", CHANNEL, e.getMessage());
        }
    }
    /**
     * Initialize channel objects
     */
    /**
     * Apply a RecordListener to get notified if a new value is available for a channel
     */
    private void initUpdateTimer() {
        updateTimer = new Timer("BMS Update Timer", true);

        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                updateChannel();
            }
        };
        updateTimer.scheduleAtFixedRate(task, (long) 1 * 1000, (long) 1 * 1000);
    }
   
}
