package org.openmuc.framework.server.restws.servlets;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmuc.framework.lib.rest1.ToJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RebootServlet extends GenericServlet {

    private static final Logger logger = LoggerFactory.getLogger(RebootServlet.class);

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        ToJson json = new ToJson();

        try {
            logger.info("Reboot request received. Executing 'sudo reboot'...");
            Runtime.getRuntime().exec("sudo reboot");
            
            response.setStatus(HttpServletResponse.SC_OK);
            CommonResponse.toSuccessResult("Reboot command issued.", json);

        } catch (Exception e) {
            logger.error("Error executing reboot command", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            CommonResponse.toExceptionResult(e.getMessage(), json);
        }

        sendJson(json, response);
    }
}
