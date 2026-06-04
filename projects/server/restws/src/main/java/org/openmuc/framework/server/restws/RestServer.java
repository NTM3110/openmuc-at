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
package org.openmuc.framework.server.restws;

import org.openmuc.framework.authentication.AuthenticationService;
import org.openmuc.framework.config.ConfigService;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.datalogger.sql.SqlLoggerService;
import org.openmuc.framework.lib.rest1.Const;
import org.openmuc.framework.lib.rest1.service.impl.OsgiJdbcUtil;
import org.openmuc.framework.server.restws.servlets.*;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpService;
import org.osgi.service.jdbc.DataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.openmuc.framework.lib.rest1.sql.SoHScheduleRepoImpl;
import org.openmuc.framework.lib.rest1.sql.EntityRepoImpl;
import org.openmuc.framework.lib.rest1.service.impl.ASyncServiceImpl;
import org.openmuc.framework.lib.rest1.domain.model.SoHSchedule;
import org.openmuc.framework.lib.rest1.common.enums.DischargeState;
import org.openmuc.framework.lib.rest1.common.enums.Status;

import javax.sql.DataSource;
import java.time.LocalDateTime;

@Component
public final class RestServer {

    private static final Logger logger = LoggerFactory.getLogger(RestServer.class);

    private static DataAccessService dataAccessService;
    private static AuthenticationService authenticationService;
    private static ConfigService configService;
    private static HttpService httpService;

    private Timer updateTimer;
    private SoHScheduleRepoImpl sohScheduleRepoImpl = new SoHScheduleRepoImpl();
    private EntityRepoImpl entityRepoImpl = new EntityRepoImpl();
    private ASyncServiceImpl asyncService = new ASyncServiceImpl();

    private final ChannelResourceServlet chRServlet = new ChannelResourceServlet();
    private final DeviceResourceServlet devRServlet = new DeviceResourceServlet();
    private final DeviceResourceServlet_v2 devRServlet_v2 = new DeviceResourceServlet_v2();
    private final NetworkRestServlet netRServlet = new NetworkRestServlet();
    private final WifiRestServlet wifiRServlet = new WifiRestServlet();
    private final DriverResourceServlet drvRServlet = new DriverResourceServlet();
    private final ConnectServlet connectServlet = new ConnectServlet();
    private final UserServlet userServlet = new UserServlet();
    private final LatestValueResourceServlet latestValueServlet = new LatestValueResourceServlet();
    private final SoHScheduleResourceServlet sohScheduleServlet = new SoHScheduleResourceServlet();
    private final BatteryStringResourceServlet batteryStringResourceServlet = new BatteryStringResourceServlet();
    private final IrTestServlet irTestServlet = new IrTestServlet();
    private final TcCalibrationServlet tcCalibrationServlet = new TcCalibrationServlet();
    private final GpioAlarmService gpioAlarmService = new GpioAlarmService();
    private final GpioAlarmServlet gpioAlarmServlet = new GpioAlarmServlet(gpioAlarmService);
    // private final ControlsServlet controlsServlet = new ControlsServlet();
    private ResetToDefaultServlet resetToDefaultServlet;

    @Reference
    private DataSourceFactory dataSourceFactory;

    public static DataAccessService getDataAccessService() {
        return RestServer.dataAccessService;
    }

    @Reference
    protected void setDataAccessService(DataAccessService dataAccessService) {
        RestServer.dataAccessService = dataAccessService;
    }

    public static ConfigService getConfigService() {
        return RestServer.configService;
    }

    @Reference
    protected void setConfigService(ConfigService configService) {
        RestServer.configService = configService;
    }

    public static AuthenticationService getAuthenticationService() {
        return RestServer.authenticationService;
    }

    @Reference
    protected void setAuthenticationService(AuthenticationService authenticationService) {
        RestServer.authenticationService = authenticationService;
    }

    private void initUpdateTimer() {
        updateTimer = new Timer("Update SoH Schedule", true); // daemon thread

        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                try {
                    // System.out.println("\n[" + LocalDateTime.now() + "] Timer task running to
                    // update SoH Schedule");
                    LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
                    List<SoHSchedule> qualifiedSchedules = sohScheduleRepoImpl
                            .findByStartDatetimeBeforeAndStateAndStatus(now, DischargeState.PENDING, Status.ACTIVE);

                    // System.out.println("Found " + qualifiedSchedules.size() + " pending
                    // schedules");

                    for (SoHSchedule schedule : qualifiedSchedules) {
                        // System.out.println("\n>>> Starting SoH calculation for Schedule ID: " +
                        // schedule.getId()
                        // + ", String ID: " + schedule.getStrId());

                        Double socValue = entityRepoImpl.getSocValue(schedule.getStrId());
                        // System.out.println(" Current SoC value: " + socValue);

                        if (Objects.isNull(socValue)) {
                            schedule.setSocBefore(100D);
                            // System.out.println(" Set SoC before to default: 100.0");
                        } else {
                            schedule.setSocBefore(socValue);
                            System.out.println("    Set SoC before to: " + socValue);
                        }

                        schedule.setState(DischargeState.RUNNING);
                        schedule.setUpdateDatetime(LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
                        sohScheduleRepoImpl.save(schedule);
                        // System.out.println(" Schedule state updated to RUNNING");

                        // Trigger async calculation
                        // System.out.println(" Triggering async SoH calculation...");
                        asyncService.calculateSoh(schedule.getId(), schedule.getStrId());
                    }

                } catch (Exception e) {
                    System.err.println("Error in timer task: " + e.getMessage());
                    e.printStackTrace();
                }
                try {
                    gpioAlarmService.updateOutputs(dataAccessService);
                } catch (Exception e) {
                    logger.warn("GPIO alarm update failed: {}", e.toString());
                }
            }
        };

        // Schedule at fixed rate: initial delay 1 second, repeat every 1 second
        updateTimer.scheduleAtFixedRate(task, 1000, 1000);
        System.out.println("Timer initialized and scheduled at fixed rate (1 second interval)");
    }

    @Activate
    protected void activate(ComponentContext context) throws Exception {
        logger.info("Activating REST Server");
        // BundleContext bc = context.getBundleContext();
        BundleContext bc = FrameworkUtil.getBundle(SqlLoggerService.class).getBundleContext();
        for (Bundle bundle : bc.getBundles()) {
            if (bundle.getSymbolicName() == null) {
                continue;
            }
            if (bundle.getSymbolicName().equals("org.postgresql.jdbc")) {
                dataSourceFactory = (DataSourceFactory) bundle.loadClass("org.postgresql.osgi.PGDataSourceFactory")
                        .getDeclaredConstructors()[0].newInstance();
            }
        }
        Properties properties = new Properties();
        properties.setProperty("url", "jdbc:postgresql://localhost:5432/openmuc");
        properties.setProperty("password", "openmuc");
        properties.setProperty("user", "openmuc_user");
        DataSource ds = dataSourceFactory.createDataSource(properties);
        //
        ExportLatestValuesCsvServlet exportLatestValuesCsvServlet = new ExportLatestValuesCsvServlet(ds, dataAccessService, configService);
        ExportCsvRangeServlet exportCsvRangeServlet = new ExportCsvRangeServlet();
        resetToDefaultServlet = new ResetToDefaultServlet(ds, configService);
        SecurityHandler securityHandler = new SecurityHandler(context.getBundleContext().getBundle(),
                authenticationService);

        httpService.registerServlet(Const.ALIAS_CHANNELS, chRServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_DEVICES, devRServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_DEVICES_V2, devRServlet_v2, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_NETWORK, netRServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_WIFI, wifiRServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_DRIVERS, drvRServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_USERS, userServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_CONNECT, connectServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_LATEST_VALUE, latestValueServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_SOH_SCHEDULE, sohScheduleServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_CSV_EXPORT, exportLatestValuesCsvServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_EXPORT_CSV_RANGE, exportCsvRangeServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_RESET, resetToDefaultServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_REBOOT, new RebootServlet(), null, securityHandler);
        httpService.registerServlet(Const.ALIAS_START_KBD, new StartKbdServlet(), null, securityHandler);
        httpService.registerServlet(Const.ALIAS_STRING, batteryStringResourceServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_IR_TEST, irTestServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_TC_CALIBRATION, tcCalibrationServlet, null, securityHandler);
        httpService.registerServlet(Const.ALIAS_GPIO_ALARM, gpioAlarmServlet, null, securityHandler);
        // httpService.registerServlet(Const.ALIAS_CONTROLS, controlsServlet, null,
        // securityHandler);
        initUpdateTimer();
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        logger.info("Deactivating REST Server");

        httpService.unregister(Const.ALIAS_CHANNELS);
        httpService.unregister(Const.ALIAS_DEVICES);
        httpService.unregister(Const.ALIAS_DRIVERS);
        httpService.unregister(Const.ALIAS_USERS);
        httpService.unregister(Const.ALIAS_CONNECT);
        httpService.unregister(Const.ALIAS_LATEST_VALUE);
        httpService.unregister(Const.ALIAS_DEVICES_V2);
        httpService.unregister(Const.ALIAS_NETWORK);
        httpService.unregister(Const.ALIAS_WIFI);
        httpService.unregister(Const.ALIAS_SOH_SCHEDULE);
        httpService.unregister(Const.ALIAS_CSV_EXPORT);
        httpService.unregister(Const.ALIAS_EXPORT_CSV_RANGE);
        httpService.unregister(Const.ALIAS_RESET);
        httpService.unregister(Const.ALIAS_REBOOT);
        httpService.unregister(Const.ALIAS_START_KBD);
        httpService.unregister(Const.ALIAS_IR_TEST);
        httpService.unregister(Const.ALIAS_TC_CALIBRATION);
        httpService.unregister(Const.ALIAS_GPIO_ALARM);
        // httpService.unregister(Const.ALIAS_CONTROLS);

        updateTimer.cancel();
        updateTimer.purge();
    }

    protected void unsetConfigService(ConfigService configService) {
        RestServer.configService = null;
    }

    protected void unsetAuthenticationService(AuthenticationService authenticationService) {
        RestServer.authenticationService = null;
    }

    @Reference
    protected void setHttpService(HttpService httpService) {
        RestServer.httpService = httpService;
    }

    protected void unsetHttpService(HttpService httpService) {
        RestServer.httpService = null;
    }

    protected void unsetDataAccessService(DataAccessService dataAccessService) {
        RestServer.dataAccessService = null;
    }

}
