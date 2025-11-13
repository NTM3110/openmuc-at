package org.openmuc.framework.app.bms;

import org.openmuc.framework.data.Record;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.dataaccess.RecordListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Timer;
import java.util.TimerTask;

@Component(service = {})
public final class BMSApp{

	private static final Logger logger = LoggerFactory.getLogger(BMSApp.class);
	private static final String APP_NAME = "OpenMUC BMS App";

	boolean isRestored = false;
    // private LatestValuesDao latestValuesDao = new LatestValuesDao();
    // private LatestValuesRestorer latestValuesRestorer = new LatestValuesRestorer();

	private Channel channel;
	private RecordListener channelListener;

	private Timer updateTimer;

	@Reference
	private DataAccessService dataAccessService;

	@Activate
	private void activate() {
		logger.info("Activating {}", APP_NAME);
		channelListener = new ChannelListener();
        // LatestValuesRestorer.restoreAll();
		channel = dataAccessService.getChannel("site_name_1");
		channel.addListener(channelListener);
		initUpdateTimer();
	}

	@Deactivate
	private void deactivate() {
		logger.info("Deactivating {}", APP_NAME);
		channel.removeListener(channelListener);
	}
	
	private void initUpdateTimer() {
        updateTimer = new Timer("Modbus Update");
	
	    TimerTask task = new TimerTask() {
	        @Override
	        public void run() {
				if(isRestored == false) {
					if(LatestValuesRestorer.restoreAll(dataAccessService) > 0) {
						isRestored = true;
					}
				}
	        }
	    };
	    updateTimer.scheduleAtFixedRate(task, (long) 1 * 1000, (long) 1 * 1000);
	}
}

class ChannelListener implements RecordListener{

	private static final Logger logger = LoggerFactory.getLogger(ChannelListener.class);

	@Override
	public void newRecord(Record record) {
		if (record.getValue() != null) {
			logger.info(">>> channel value: {}", record.getValue().asString());
            LatestValuesDao.updateString("site_name_1", record.getValue().asString());
		}
	}
}