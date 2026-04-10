package org.openmuc.framework.lib.rest1.service.impl;

import org.openmuc.framework.lib.rest1.service.ASyncService;
import org.openmuc.framework.lib.rest1.domain.model.SoHSchedule;
import org.openmuc.framework.lib.rest1.common.enums.DischargeState;

import java.util.concurrent.CompletableFuture;
import java.time.LocalDateTime;

import org.openmuc.framework.lib.rest1.sql.SoHScheduleRepoImpl;
import org.openmuc.framework.lib.rest1.sql.LatestValueRepoImpl;
import org.openmuc.framework.lib.rest1.sql.EntityRepoImpl;
import org.openmuc.framework.lib.rest1.domain.model.LatestValue;

import static org.openmuc.framework.lib.rest1.common.enums.Status.ACTIVE;
import static org.openmuc.framework.lib.rest1.common.enums.DischargeState.RUNNING;
import static org.openmuc.framework.lib.rest1.common.enums.DischargeState.STOPPED;
import org.openmuc.framework.lib.rest1.common.utils.TemperatureFactor;

import java.util.Objects;
import java.util.Arrays;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ASyncServiceImpl implements ASyncService {
    private final SoHScheduleRepoImpl sohScheduleRepoImpl = new SoHScheduleRepoImpl();
    // private LatestValueRepoImpl latestValueRepoImpl;
    private final EntityRepoImpl entityRepoImpl = new EntityRepoImpl();
    public static final int INTERVAL = 1;
    private static final Logger logger = LoggerFactory.getLogger(ASyncServiceImpl.class);
    
    @Override
    public CompletableFuture<String> calculateSoh(Long id, String strId) {
        // TODO Auto-generated method stub
        return CompletableFuture.runAsync(() -> {
            boolean shouldContinue = true;
            try{
                while (shouldContinue) {
                    // System.out.println("JUST DUMP messagesa\n");
                    System.out.println("Calculating SoH for SoH Schedule ID: " + id + ", String ID: " + strId);
                    SoHSchedule sohSchedule = sohScheduleRepoImpl.findByIdAndStateInAndStatus(id, Arrays.asList(RUNNING,STOPPED),ACTIVE);
                    System.out.println("SoH Schedule found here: " + sohSchedule);
                    if(sohSchedule == null){
                        System.out.println("No SoH Schedule found with ID: " + id + " in RUNNING or STOPPED state.");
                        // Thread.currentThread().interrupt();
                        shouldContinue = false;
                        break;
                    }
                    if (sohSchedule.getState().equals(DischargeState.STOPPED)) {
                        System.out.println("SoH Schedule with ID: " + id + " has been STOPPED. Finalizing as SUCCESS.");
                        try {
                            stopThreadSuccess(sohSchedule);
                            System.out.println("SoH Schedule with ID: " + id + " finalized as SUCCESS.");
                        } catch (Exception stopEx) {
                            System.out.println("ERROR finalizing schedule " + id + " as SUCCESS: " + stopEx);
                            stopEx.printStackTrace();
                        }
                        shouldContinue = false;
                        break;
                    }

                    LatestValue CnominalValue = LatestValueRepoImpl.findLatestValueByChannelId(strId + "_Cnominal");
                    if (CnominalValue == null) {
                        System.out.println("Cnominal value not found for String ID: " + strId);
                        stopThreadFail(sohSchedule);
                        shouldContinue = false;
                        break;
                    }
                    if (Objects.isNull(CnominalValue.getValueDouble())) {
                        System.out.println("Cnominal value is null for String ID: " + strId);
                        stopThreadFail(sohSchedule);
                        shouldContinue = false;
                        break;
                    }
                    double cNominal = CnominalValue.getValueDouble();

                    Double socValueAfter = entityRepoImpl.getSocValue(sohSchedule.getStrId());
                    if (Objects.isNull(socValueAfter)) {
                        System.out.println("SoC value is null for String ID: " + strId);
                        stopThreadFail(sohSchedule);
                        shouldContinue = false;
                        break;
                    }

                    double cNominalAs = cNominal * 3600;
                    double usedQ  = sohSchedule.getUsedQ();
                    double current = entityRepoImpl.getCurrentValue(sohSchedule.getStrId());
                    double temperature = entityRepoImpl.getTemperatureValue(sohSchedule.getStrId());
                    usedQ += current * INTERVAL * TemperatureFactor.getFactor(temperature);
                    double soh;
                    if (sohSchedule.getSocBefore() - socValueAfter == 0) {
                        soh = 100d;
                    } else {
                        soh = Math.abs(usedQ / (sohSchedule.getSocBefore() - socValueAfter) / cNominalAs * 10000);
                    }
                    if (soh > 100) {
                        soh = 100d;
                    }
                    sohSchedule.setSoh(soh);
                    sohSchedule.setUsedQ(usedQ);
                    sohSchedule.setSocAfter(socValueAfter);
                    sohSchedule.setUpdateDatetime(LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
                    sohScheduleRepoImpl.save(sohSchedule);

                    Thread.sleep(INTERVAL * 1000);
                }
            } catch (Exception e) {
                System.out.println("Error in calculateSoh: " + e);
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }).thenApply(v -> "Task completed");
    }

    private void stopThreadSuccess(SoHSchedule sohSchedule){
        sohSchedule.setState(DischargeState.SUCCESS);
        sohSchedule.setEndDatetime(LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        sohScheduleRepoImpl.save(sohSchedule);
        // Thread.currentThread().interrupt();
        // return CompletableFuture.completedFuture("Task completed successfully");
    }

    private void stopThreadFail(SoHSchedule sohSchedule){
        sohSchedule.setState(DischargeState.FAILED);
        sohSchedule.setEndDatetime(LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        sohScheduleRepoImpl.save(sohSchedule);
        // Thread.currentThread().interrupt();
        // return CompletableFuture.completedFuture("Task completed failed");
    }
}
