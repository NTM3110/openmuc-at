package org.openmuc.framework.server.restws.servlets;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmuc.framework.lib.rest1.ToJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StartKbdServlet extends GenericServlet {

    private static final Logger logger = LoggerFactory.getLogger(StartKbdServlet.class);

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        ToJson json = new ToJson();

        try {
            String type = request.getParameter("type");
            logger.info("start_kbd request received. Type: {}", type);

            String scriptCmd;
            if ("number".equals(type)) {
                logger.info("Starting number keyboard");
                scriptCmd = "/home/admin/start_kbd.sh number";
            } else {
                logger.info("Starting normal keyboard");
                scriptCmd = "/home/admin/start_kbd.sh";
            }

            // Using nohup and & ensures the process backgrounds and survives the shell exit
            String fullCmd = "nohup " + scriptCmd + " &";
            
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", fullCmd);
            java.util.Map<String, String> env = pb.environment();
            env.put("DISPLAY", ":0");
            env.put("XAUTHORITY", "/home/admin/.Xauthority");
            env.put("WAYLAND_DISPLAY", "wayland-1"); 
            env.put("XDG_RUNTIME_DIR", "/run/user/1000");
            
            // Native Java redirection is more reliable than shell redirection
            java.io.File logFile = new java.io.File("/home/admin/kbd.log");
            pb.redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(logFile));
            pb.redirectError(java.lang.ProcessBuilder.Redirect.appendTo(logFile));
            
            logger.info("Executing command: {} with native redirection to {}", fullCmd, logFile.getAbsolutePath());
            pb.start();
            
            response.setStatus(HttpServletResponse.SC_OK);
            CommonResponse.toSuccessResult("start_kbd command issued. Log: " + logFile.getAbsolutePath(), json);

        } catch (Exception e) {
            logger.error("Error executing start_kbd command", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            CommonResponse.toExceptionResult(e.getMessage(), json);
        }

        sendJson(json, response);
    }
}
